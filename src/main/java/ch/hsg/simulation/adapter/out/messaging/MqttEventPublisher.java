package ch.hsg.simulation.adapter.out.messaging;

import ch.hsg.simulation.adapter.TemplateValues;
import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.net.NetDefinition.EventTopic;
import ch.hsg.simulation.core.net.Templates;
import ch.hsg.simulation.core.port.SimulationControl;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Driven adapter: publishes every MQTT message a layout declares, for the most recently started
 * running simulation of that layout, at the declared interval. Like the physical stations it
 * publishes the full state each time, not only changes, reading sensors (faults applied) through
 * the control port. Reconnects quietly when the broker is not there.
 */
@Component
@ConditionalOnProperty(name = "simulation.events.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(MqttEventPublisher.class);
    private static final Duration DEFAULT_INTERVAL = Duration.ofMillis(500);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(10);

    private final SimulationControl control;
    private final String brokerUrl;
    private final String username;
    private final String password;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mqtt-event-publisher");
        thread.setDaemon(true);
        return thread;
    });

    private MqttClient client;
    private long nextConnectAttempt;
    private boolean warned;

    public MqttEventPublisher(SimulationControl control,
                              @Value("${simulation.events.mqtt.broker-url:tcp://localhost:1883}") String brokerUrl,
                              @Value("${simulation.events.mqtt.username:}") String username,
                              @Value("${simulation.events.mqtt.password:}") String password) {
        this.control = control;
        this.brokerUrl = brokerUrl;
        this.username = username;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        for (NetDefinition layout : control.layouts()) {
            for (EventTopic event : layout.events()) {
                if (!"mqtt".equals(event.protocol())) {
                    LOG.warn("Layout {}: protocol {} of {} is not supported, skipped", layout.id(),
                            event.protocol(), event.topic());
                    continue;
                }
                Duration every = event.every() == null ? DEFAULT_INTERVAL : event.every();
                scheduler.scheduleAtFixedRate(() -> tick(layout, event), 0, every.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (MqttException exception) {
                LOG.debug("Closing MQTT client failed", exception);
            }
        }
    }

    private void tick(NetDefinition layout, EventTopic event) {
        try {
            Optional<UUID> running = control.running(layout.id());
            if (running.isEmpty() || !connected()) {
                return;
            }
            Map<String, Object> sensors = control.readSensors(running.get());
            TemplateValues values = new TemplateValues().sensors(() -> sensors).now(LocalDateTime.now());
            String payload = Templates.render(event.payload(), values);
            client.publish(event.topic(), payload.getBytes(StandardCharsets.UTF_8), 0, false);
        } catch (MqttException | RuntimeException exception) {
            LOG.warn("Publishing {} failed: {}", event.topic(), exception.getMessage());
        }
    }

    private synchronized boolean connected() {
        if (client != null && client.isConnected()) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now < nextConnectAttempt) {
            return false;
        }
        nextConnectAttempt = now + RECONNECT_DELAY.toMillis();
        try {
            if (client == null) {
                client = new MqttClient(brokerUrl, "simulation-platform-" + UUID.randomUUID(), new MemoryPersistence());
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(3);
            if (!username.isEmpty()) {
                options.setUserName(username);
                options.setPassword(password.toCharArray());
            }
            client.connect(options);
            LOG.info("Publishing layout events to {}", brokerUrl);
            warned = false;
            return true;
        } catch (MqttException exception) {
            if (!warned) {
                LOG.warn("MQTT broker {} not reachable ({}); retrying every {} s",
                        brokerUrl, exception.getMessage(), RECONNECT_DELAY.toSeconds());
                warned = true;
            }
            return false;
        }
    }
}
