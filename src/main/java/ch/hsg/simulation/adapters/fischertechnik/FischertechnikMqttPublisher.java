package ch.hsg.simulation.adapters.fischertechnik;

import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.runtime.SimulationManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hardware-compatible MQTT output of the Fischertechnik factory (ADR 0009): like the physical
 * controllers, every station publishes its full state to {@code FTFactory/<STATION>} at a fixed
 * rate (default twice per second), QoS 0, not retained. Publishes for the one running simulation
 * of the layout only. A missing broker is retried in the background and never blocks the simulator.
 */
@Component
@ConditionalOnProperty(name = "simulation.fischertechnik.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class FischertechnikMqttPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(FischertechnikMqttPublisher.class);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);

    private final SimulationManager manager;
    private final ObjectMapper json;
    private final String brokerUrl;
    private final String topicPrefix;
    private final Duration interval;
    private final String username;
    private final String password;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("fischertechnik-mqtt").daemon().factory());
    private MqttClient client;
    private long nextConnectAttempt;
    private boolean warned;

    public FischertechnikMqttPublisher(SimulationManager manager, ObjectMapper json,
                                       @Value("${simulation.fischertechnik.mqtt.broker-url:tcp://localhost:1883}") String brokerUrl,
                                       @Value("${simulation.fischertechnik.mqtt.topic-prefix:FTFactory/}") String topicPrefix,
                                       @Value("${simulation.fischertechnik.mqtt.interval:500ms}") Duration interval,
                                       @Value("${simulation.fischertechnik.mqtt.username:}") String username,
                                       @Value("${simulation.fischertechnik.mqtt.password:}") String password) {
        this.manager = manager;
        this.json = json;
        this.brokerUrl = brokerUrl;
        this.topicPrefix = topicPrefix;
        this.interval = interval;
        this.username = username;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleAtFixedRate(this::tick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
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

    private void tick() {
        try {
            List<UUID> running = RunningFactory.find(manager);
            if (running.size() != 1 || !connected()) {
                return;
            }
            SimulationSnapshot snapshot = manager.getSimulation(running.get(0));
            LocalDateTime now = LocalDateTime.now();
            for (String station : FischertechnikMqttPayloads.STATIONS.keySet()) {
                publish(topicPrefix + station, FischertechnikMqttPayloads.payload(station, snapshot, now));
            }
        } catch (RuntimeException exception) {
            LOG.warn("Publishing factory MQTT messages failed: {}", exception.getMessage());
        }
    }

    private boolean connected() {
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
            LOG.info("Publishing factory MQTT messages to {}", brokerUrl);
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

    private void publish(String topic, Object payload) {
        try {
            client.publish(topic, json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8), 0, false);
        } catch (JsonProcessingException | MqttException exception) {
            LOG.warn("Publishing to {} failed: {}", topic, exception.getMessage());
        }
    }
}
