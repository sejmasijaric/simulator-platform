package ch.hsg.simulation.core.runtime;

import ch.hsg.simulation.core.NotFoundException;
import ch.hsg.simulation.core.SimulationException;
import ch.hsg.simulation.core.event.EventLog;
import ch.hsg.simulation.core.event.EventType;
import ch.hsg.simulation.core.event.SimulationEvent;
import ch.hsg.simulation.core.fault.Faults;
import ch.hsg.simulation.core.fault.NetFault;
import ch.hsg.simulation.core.fault.SensorFault;
import ch.hsg.simulation.core.net.Marking;
import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.net.NetInterpreter;
import ch.hsg.simulation.core.net.NetInterpreter.Firing;
import ch.hsg.simulation.core.net.Templates;
import ch.hsg.simulation.core.net.Token;
import ch.hsg.simulation.core.port.CommandHandle;
import ch.hsg.simulation.core.port.SimulationStatus;
import ch.hsg.simulation.core.port.Snapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Runs one simulation of one layout. All operations run on a single thread, one after the
 * other (ADR 0005); the engine is the only thing that moves time and changes the marking.
 *
 * <p>After every change it fires whatever the net allows: a firing takes its input tokens at
 * once and gives its outputs back {@code duration} later. Time moves only through
 * {@link #step()}, {@link #runUntil(Duration)} or a {@link RealTimeDriver}; the engine never
 * reads the system clock.
 */
public final class SimulationEngine implements AutoCloseable {

    public static final String ORIGIN_CONTROL = "control";
    public static final String ORIGIN_APPLICATION = "application";
    public static final String ORIGIN_SIMULATION = "simulation";

    /** Guards against a net whose zero-duration transitions fire each other forever. */
    private static final int MAX_FIRINGS_PER_INSTANT = 10_000;

    private record InProgress(Firing firing, Duration started) {
    }

    private record PendingCommand(Set<String> returnsAfter, CompletableFuture<SimulationEvent> completion) {
    }

    private final UUID id;
    private final NetDefinition net;
    private final NetInterpreter interpreter;
    private final EventQueue<InProgress> queue = new EventQueue<>();
    private final EventLog log = new EventLog();
    private final Faults faults = new Faults();
    private final Map<UUID, PendingCommand> pending = new HashMap<>();
    private final Map<UUID, String> commandOrigins = new HashMap<>();

    private SimulationStatus status = SimulationStatus.CREATED;
    private Duration now = Duration.ZERO;
    private Marking marking;
    private Set<String> activeModules = Set.of();
    private final Map<String, Integer> settings = new LinkedHashMap<>();
    private long tokenSequence;

    private final ExecutorService loop;
    private volatile Thread loopThread;

    public SimulationEngine(UUID id, NetDefinition net) {
        this.id = Objects.requireNonNull(id, "id");
        this.net = Objects.requireNonNull(net, "net");
        this.interpreter = new NetInterpreter(net);
        this.marking = Marking.initial(net);
        net.settings().forEach(setting -> settings.put(setting.name(), setting.initial()));
        this.loop = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "simulation-" + id);
            thread.setDaemon(true);
            loopThread = thread;
            return thread;
        });
    }

    public UUID id() {
        return id;
    }

    public NetDefinition net() {
        return net;
    }

    // ---- lifecycle ----

    public void start() {
        call(() -> {
            if (status == SimulationStatus.RUNNING) {
                throw new SimulationException("Simulation " + id + " is already running");
            }
            status = SimulationStatus.RUNNING;
            append(EventType.SIMULATION_STARTED, null, null, ORIGIN_CONTROL, Map.of());
            fireEnabled();
            return null;
        });
    }

    public void stop() {
        call(() -> {
            requireRunning();
            status = SimulationStatus.STOPPED;
            append(EventType.SIMULATION_STOPPED, null, null, ORIGIN_CONTROL, Map.of());
            return null;
        });
    }

    public void reset() {
        call(() -> {
            status = SimulationStatus.CREATED;
            now = Duration.ZERO;
            marking = Marking.initial(net);
            queue.clear();
            faults.clear();
            activeModules = Set.of();
            net.settings().forEach(setting -> settings.put(setting.name(), setting.initial()));
            pending.values().forEach(command -> command.completion().cancel(false));
            pending.clear();
            commandOrigins.clear();
            tokenSequence = 0;
            log.clear();
            append(EventType.SIMULATION_RESET, null, null, ORIGIN_CONTROL, Map.of());
            return null;
        });
    }

    public SimulationStatus status() {
        return call(() -> status);
    }

    public Duration now() {
        return call(() -> now);
    }

    // ---- the layout's own interface ----

    /** Convenience for a request that ends with one transition (or, with null, at once). */
    public CommandHandle command(String commandPlace, Map<String, ?> parameters, String returnsAfter, String origin) {
        return command(commandPlace, parameters, returnsAfter == null ? List.of() : List.of(returnsAfter), origin);
    }

    /**
     * Puts a request token into {@code commandPlace}. The handle completes when any of
     * {@code returnsAfter} fires for this request; with none it is complete at once.
     */
    public CommandHandle command(String commandPlace, Map<String, ?> parameters, Collection<String> returnsAfter,
                                 String origin) {
        Set<String> endings = returnsAfter == null ? Set.of() : Set.copyOf(returnsAfter);
        return call(() -> {
            requireRunning();
            requirePlace(commandPlace);
            for (String transition : endings) {
                if (net.transition(transition).isEmpty()) {
                    throw new NotFoundException("Unknown transition: " + transition);
                }
            }
            UUID commandId = UUID.randomUUID();
            String by = origin == null ? ORIGIN_APPLICATION : origin;
            commandOrigins.put(commandId, by);
            Token request = new Token(nextTokenId("request"), copy(parameters), commandId);
            marking.add(commandPlace, request);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", request.id());
            data.put("parameters", request.attributes());
            data.put("returnsAfter", List.copyOf(endings));
            SimulationEvent received = append(EventType.COMMAND_RECEIVED, commandPlace, commandId, by, data);
            CompletableFuture<SimulationEvent> completion = new CompletableFuture<>();
            if (endings.isEmpty()) {
                completion.complete(received);
            } else {
                pending.put(commandId, new PendingCommand(endings, completion));
            }
            fireEnabled();
            return new CommandHandle(commandId, completion);
        });
    }

    public Map<String, Object> readSensors() {
        return call(() -> faults.apply(now, trueSensorValues()));
    }

    // ---- preparing and disturbing a run (any lifecycle state) ----

    public String placeToken(String place, String tokenId, Map<String, ?> attributes) {
        return call(() -> {
            requirePlace(place);
            int capacity = net.capacityOf(place);
            if (capacity != NetDefinition.UNLIMITED && marking.count(place) >= capacity) {
                throw new IllegalArgumentException("Place " + place + " is full");
            }
            String idToUse = tokenId == null || tokenId.isBlank() ? nextTokenId("token") : tokenId;
            if (tokenExists(idToUse)) {
                throw new IllegalArgumentException("Token " + idToUse + " already exists");
            }
            marking.add(place, new Token(idToUse, copy(attributes), null));
            append(EventType.TOKEN_PLACED, place, null, ORIGIN_CONTROL,
                    Map.of("token", idToUse, "attributes", copy(attributes)));
            afterChange();
            return idToUse;
        });
    }

    public void removeToken(String tokenId) {
        call(() -> {
            String place = marking.remove(tokenId);
            append(EventType.TOKEN_REMOVED, place, null, ORIGIN_CONTROL, Map.of("token", tokenId));
            afterChange();
            return null;
        });
    }

    public void setSensorFault(String sensor, SensorFault fault) {
        call(() -> {
            if (net.sensor(sensor).isEmpty()) {
                throw new NotFoundException("Unknown sensor: " + sensor);
            }
            if (fault == null) {
                faults.clearSensorFault(sensor);
                append(EventType.FAULT_CLEARED, sensor, null, ORIGIN_CONTROL, Map.of("level", "sensor"));
            } else {
                faults.setSensorFault(sensor, fault, trueSensorValues().get(sensor), now);
                append(EventType.FAULT_ACTIVATED, sensor, null, ORIGIN_CONTROL,
                        Map.of("level", "sensor", "fault", fault.type().name()));
            }
            return null;
        });
    }

    public void setNetFault(String transition, NetFault fault) {
        call(() -> {
            if (net.transition(transition).isEmpty()) {
                throw new NotFoundException("Unknown transition: " + transition);
            }
            if (fault == null) {
                faults.clearNetFault(transition);
                append(EventType.FAULT_CLEARED, transition, null, ORIGIN_CONTROL, Map.of("level", "net"));
            } else {
                if (fault.place() != null) {
                    requirePlace(fault.place());
                }
                faults.setNetFault(transition, fault);
                append(EventType.FAULT_ACTIVATED, transition, null, ORIGIN_CONTROL,
                        Map.of("level", "net", "fault", fault.type().name()));
            }
            afterChange();
            return null;
        });
    }

    /** Changes a setting, clamped to its range; returns the value it now has. Allowed in every state. */
    public int setSetting(String name, int value, String origin) {
        return call(() -> {
            NetDefinition.Setting setting = net.setting(name)
                    .orElseThrow(() -> new NotFoundException("Unknown setting: " + name));
            int clamped = setting.clamp(value);
            settings.put(name, clamped);
            append(EventType.SETTING_CHANGED, name, null, origin == null ? ORIGIN_CONTROL : origin,
                    Map.of("value", clamped));
            afterChange();
            return clamped;
        });
    }

    /** Puts settings back to their initial values. */
    public void resetSettings(java.util.Collection<String> names, String origin) {
        call(() -> {
            for (String name : names) {
                NetDefinition.Setting setting = net.setting(name)
                        .orElseThrow(() -> new NotFoundException("Unknown setting: " + name));
                settings.put(name, setting.initial());
                append(EventType.SETTING_CHANGED, name, null, origin == null ? ORIGIN_CONTROL : origin,
                        Map.of("value", setting.initial(), "reset", true));
            }
            afterChange();
            return null;
        });
    }

    public void setActiveModules(Set<String> modules) {
        call(() -> {
            activeModules = modules == null ? Set.of() : Set.copyOf(modules);
            append(EventType.MODULES_CHANGED, null, null, ORIGIN_CONTROL, Map.of("modules", List.copyOf(activeModules)));
            afterChange();
            return null;
        });
    }

    // ---- time ----

    /** Ends the next firing in progress, jumping time to it. False if nothing is in progress. */
    public boolean step() {
        return call(() -> {
            requireRunning();
            Optional<EventQueue.Entry<InProgress>> next = queue.poll();
            if (next.isEmpty()) {
                return false;
            }
            finish(next.get());
            fireEnabled();
            return true;
        });
    }

    /** Ends every firing due up to {@code target}, then moves time to it (never backwards). */
    public void runUntil(Duration target) {
        Objects.requireNonNull(target, "target");
        call(() -> {
            requireRunning();
            while (queue.peek().filter(entry -> entry.time().compareTo(target) <= 0).isPresent()) {
                finish(queue.poll().orElseThrow());
                fireEnabled();
            }
            if (target.compareTo(now) > 0) {
                now = target;
            }
            return null;
        });
    }

    /** Ends firings until nothing is in progress (bounded, for nets that never settle). */
    public void runUntilIdle() {
        call(() -> {
            requireRunning();
            for (int count = 0; count < MAX_FIRINGS_PER_INSTANT && !queue.isEmpty(); count++) {
                finish(queue.poll().orElseThrow());
                fireEnabled();
            }
            return null;
        });
    }

    // ---- observation ----

    public Snapshot snapshot() {
        return call(() -> {
            List<Snapshot.RunningFiring> running = new ArrayList<>();
            for (EventQueue.Entry<InProgress> entry : queue.entries()) {
                Firing firing = entry.item().firing();
                running.add(new Snapshot.RunningFiring(firing.transition().name(),
                        firing.consumed().stream().map(NetInterpreter.Consumed::token).toList(),
                        firing.commandId(), entry.item().started(), entry.time()));
            }
            return new Snapshot(id, net.id(), status, now, marking.view(), List.copyOf(running),
                    faults.apply(now, trueSensorValues()), faults.sensorFaults(), faults.netFaults(),
                    activeModules, null, settings);
        });
    }

    public List<SimulationEvent> events(int from) {
        return log.events(from);
    }

    /** Called on the engine thread after each event; must not block or call back synchronously. */
    public void addListener(Consumer<SimulationEvent> listener) {
        log.addListener(listener);
    }

    @Override
    public void close() {
        loop.shutdownNow();
        pending.values().forEach(command -> command.completion().cancel(false));
    }

    // ---- engine loop internals ----

    private void fireEnabled() {
        if (status != SimulationStatus.RUNNING) {
            return;
        }
        for (int count = 0; ; count++) {
            if (count >= MAX_FIRINGS_PER_INSTANT) {
                throw new SimulationException("Layout " + net.id() + " keeps firing at " + now
                        + "; check for a loop of zero-duration transitions");
            }
            Optional<Firing> next = interpreter.next(marking, faults, activeModules, settings, () -> nextTokenId("token"));
            if (next.isEmpty()) {
                return;
            }
            begin(next.get());
        }
    }

    private void begin(Firing firing) {
        List<Map<String, Object>> taken = new ArrayList<>();
        for (NetInterpreter.Consumed consumed : firing.consumed()) {
            marking.remove(consumed.token().id());
            taken.add(Map.of("place", consumed.place(), "token", consumed.token().id()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("consumed", taken);
        data.put("duration", firing.duration().toString());
        if (!firing.parameters().isEmpty()) {
            data.put("parameters", firing.parameters());
        }
        append(EventType.TRANSITION_STARTED, firing.transition().name(), firing.commandId(), originOf(firing), data);
        InProgress inProgress = new InProgress(firing, now);
        if (firing.duration().isZero()) {
            complete(inProgress);
        } else {
            queue.schedule(now, now.plus(firing.duration()), inProgress);
        }
    }

    private void finish(EventQueue.Entry<InProgress> entry) {
        if (entry.time().compareTo(now) > 0) {
            now = entry.time();
        }
        complete(entry.item());
    }

    private void complete(InProgress inProgress) {
        Firing firing = inProgress.firing();
        String origin = originOf(firing);
        List<Map<String, Object>> put = new ArrayList<>();
        for (NetInterpreter.Produced produced : firing.produced()) {
            marking.add(produced.place(), produced.token());
            put.add(Map.of("place", produced.place(), "token", produced.token().id()));
        }
        for (Token token : firing.lost()) {
            append(EventType.TOKEN_LOST, token.id(), firing.commandId(), origin,
                    Map.of("transition", firing.transition().name()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("produced", put);
        if (!firing.result().isEmpty()) {
            data.put("result", firing.result());
        }
        append(EventType.TRANSITION_COMPLETED, firing.transition().name(), firing.commandId(), origin, data);
        completeCommand(firing, origin);
        recordDelayedSensors();
    }

    private void completeCommand(Firing firing, String origin) {
        if (firing.commandId() == null) {
            return;
        }
        PendingCommand command = pending.get(firing.commandId());
        if (command != null && command.returnsAfter().contains(firing.transition().name())) {
            pending.remove(firing.commandId());
            SimulationEvent completed = append(EventType.COMMAND_COMPLETED, firing.transition().name(),
                    firing.commandId(), origin, Map.of("result", firing.result()));
            command.completion().complete(completed);
        }
    }

    private void afterChange() {
        fireEnabled();
        recordDelayedSensors();
    }

    private void recordDelayedSensors() {
        if (faults.hasDelayedSensors()) {
            faults.record(now, trueSensorValues());
        }
    }

    private Map<String, Object> trueSensorValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (NetDefinition.Sensor sensor : net.sensors()) {
            Object value = switch (sensor.kind()) {
                case OCCUPIED -> marking.occupied(sensor.place());
                case COUNT -> marking.count(sensor.place());
                case ATTRIBUTE -> marking.tokensAt(sensor.place()).stream().findFirst()
                        .map(token -> token.attribute(sensor.attribute())).orElse(null);
                case RUNNING -> runningText(sensor);
            };
            String key = String.valueOf(value);
            values.put(sensor.name(), sensor.values().containsKey(key) ? sensor.values().get(key) : value);
        }
        return values;
    }

    private String originOf(Firing firing) {
        return firing.commandId() == null ? ORIGIN_SIMULATION
                : commandOrigins.getOrDefault(firing.commandId(), ORIGIN_APPLICATION);
    }

    private SimulationEvent append(EventType type, String subject, UUID commandId, String origin, Map<String, ?> data) {
        return log.append(now, type, subject, commandId, origin, data);
    }

    private boolean tokenExists(String tokenId) {
        if (marking.find(tokenId).isPresent()) {
            return true;
        }
        return queue.entries().stream().flatMap(entry -> entry.item().firing().consumed().stream())
                .anyMatch(consumed -> consumed.token().id().equals(tokenId));
    }

    private String nextTokenId(String prefix) {
        String candidate;
        do {
            candidate = prefix + "-" + (++tokenSequence);
        } while (marking.find(candidate).isPresent());
        return candidate;
    }

    private void requirePlace(String place) {
        if (net.place(place).isEmpty()) {
            throw new NotFoundException("Unknown place: " + place);
        }
    }

    /** The text of the first firing in progress the sensor shows, filled from its request; "" if none. */
    private String runningText(NetDefinition.Sensor sensor) {
        for (EventQueue.Entry<InProgress> entry : queue.entries()) {
            Firing firing = entry.item().firing();
            String template = sensor.running().get(firing.transition().name());
            if (template != null) {
                return Templates.render(template, key -> {
                    int dot = key.indexOf('.');
                    if (dot > 0) {
                        String variable = key.substring(0, dot);
                        for (NetInterpreter.Consumed consumed : firing.consumed()) {
                            if (variable.equals(consumed.variable())) {
                                Object value = consumed.token().attribute(key.substring(dot + 1));
                                return value == null ? "" : value;
                            }
                        }
                    }
                    Object value = firing.parameters().get(key);
                    return value == null ? "" : value;
                });
            }
        }
        return "";
    }

    private void requireRunning() {
        if (status != SimulationStatus.RUNNING) {
            throw new SimulationException("Simulation " + id + " is not running");
        }
    }

    private static Map<String, Object> copy(Map<String, ?> map) {
        return map == null ? Map.of() : new LinkedHashMap<>(map);
    }

    private <T> T call(Callable<T> task) {
        if (Thread.currentThread() == loopThread) {
            return invoke(task);
        }
        try {
            return loop.submit(() -> invoke(task)).get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SimulationException("Simulation command failed", exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SimulationException("Interrupted while waiting for the simulation", exception);
        } catch (RejectedExecutionException exception) {
            throw new SimulationException("Simulation " + id + " is closed");
        }
    }

    private static <T> T invoke(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SimulationException("Simulation command failed", exception);
        }
    }
}
