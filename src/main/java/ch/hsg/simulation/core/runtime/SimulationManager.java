package ch.hsg.simulation.core.runtime;

import ch.hsg.simulation.core.NotFoundException;
import ch.hsg.simulation.core.SimulationException;
import ch.hsg.simulation.core.event.SimulationEvent;
import ch.hsg.simulation.core.fault.NetFault;
import ch.hsg.simulation.core.fault.SensorFault;
import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.port.CommandHandle;
import ch.hsg.simulation.core.port.LayoutSource;
import ch.hsg.simulation.core.port.SimulationControl;
import ch.hsg.simulation.core.port.SimulationListener;
import ch.hsg.simulation.core.port.SimulationStatus;
import ch.hsg.simulation.core.port.Snapshot;

import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements the control port: holds every layout from the {@link LayoutSource}, one engine per
 * simulation and, where real time is on, one {@link RealTimeDriver} per simulation.
 */
public final class SimulationManager implements SimulationControl, AutoCloseable {

    private final Map<String, NetDefinition> layouts;
    private final Map<UUID, SimulationEngine> engines = new ConcurrentHashMap<>();
    private final Map<UUID, RealTimeDriver> drivers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> startOrder = new ConcurrentHashMap<>();
    private final AtomicLong starts = new AtomicLong();
    private final List<SimulationListener> listeners = new CopyOnWriteArrayList<>();

    public SimulationManager(LayoutSource source) {
        Map<String, NetDefinition> loaded = new LinkedHashMap<>();
        for (NetDefinition net : source.load()) {
            if (loaded.put(net.id(), net) != null) {
                throw new IllegalArgumentException("Layout " + net.id() + " is defined twice");
            }
        }
        this.layouts = Collections.unmodifiableMap(loaded);
    }

    // ---- layouts and lifecycle ----

    @Override
    public List<NetDefinition> layouts() {
        return List.copyOf(layouts.values());
    }

    @Override
    public NetDefinition layout(String layoutId) {
        NetDefinition net = layouts.get(layoutId);
        if (net == null) {
            throw new NotFoundException("Unknown layout: " + layoutId);
        }
        return net;
    }

    @Override
    public UUID create(String layoutId) {
        NetDefinition net = layout(layoutId);
        UUID id = UUID.randomUUID();
        SimulationEngine engine = new SimulationEngine(id, net);
        engine.addListener(event -> notifyListeners(id, net.id(), event));
        engines.put(id, engine);
        return id;
    }

    @Override
    public void start(UUID id) {
        engine(id).start();
        startOrder.put(id, starts.incrementAndGet());
    }

    @Override
    public void stop(UUID id) {
        engine(id).stop();
    }

    @Override
    public void reset(UUID id) {
        engine(id).reset();
    }

    @Override
    public void remove(UUID id) {
        SimulationEngine engine = engine(id);
        if (engine.status() == SimulationStatus.RUNNING) {
            throw new SimulationException("Stop simulation " + id + " before removing it");
        }
        stopRealTime(id);
        engines.remove(id);
        startOrder.remove(id);
        engine.close();
    }

    @Override
    public Snapshot snapshot(UUID id) {
        Snapshot snapshot = engine(id).snapshot();
        RealTimeDriver driver = drivers.get(id);
        return driver == null ? snapshot : new Snapshot(snapshot.id(), snapshot.layoutId(), snapshot.status(),
                snapshot.time(), snapshot.marking(), snapshot.running(), snapshot.sensors(), snapshot.sensorFaults(),
                snapshot.netFaults(), snapshot.activeModules(), driver.speed());
    }

    @Override
    public List<Snapshot> snapshots() {
        return engines.keySet().stream().map(this::snapshot)
                .sorted(Comparator.comparing(snapshot -> snapshot.id().toString())).toList();
    }

    @Override
    public Optional<UUID> running(String layoutId) {
        return engines.values().stream()
                .filter(engine -> engine.net().id().equals(layoutId))
                .filter(engine -> engine.status() == SimulationStatus.RUNNING)
                .max(Comparator.comparingLong(engine -> startOrder.getOrDefault(engine.id(), 0L)))
                .map(SimulationEngine::id);
    }

    // ---- the layout's own interface ----

    @Override
    public CommandHandle command(UUID id, String commandPlace, Map<String, ?> parameters, String returnsAfter,
                                 String origin) {
        return engine(id).command(commandPlace, parameters, returnsAfter, origin);
    }

    @Override
    public Map<String, Object> readSensors(UUID id) {
        return engine(id).readSensors();
    }

    // ---- preparing and disturbing a run ----

    @Override
    public String placeToken(UUID id, String place, String tokenId, Map<String, ?> attributes) {
        return engine(id).placeToken(place, tokenId, attributes);
    }

    @Override
    public void removeToken(UUID id, String tokenId) {
        engine(id).removeToken(tokenId);
    }

    @Override
    public void setSensorFault(UUID id, String sensor, SensorFault fault) {
        engine(id).setSensorFault(sensor, fault);
    }

    @Override
    public void setNetFault(UUID id, String transition, NetFault fault) {
        engine(id).setNetFault(transition, fault);
    }

    @Override
    public void setActiveModules(UUID id, Set<String> modules) {
        engine(id).setActiveModules(modules);
    }

    // ---- time ----

    @Override
    public boolean step(UUID id) {
        return engine(id).step();
    }

    @Override
    public void runUntil(UUID id, Duration time) {
        engine(id).runUntil(time);
    }

    @Override
    public void setSpeed(UUID id, double factor) {
        SimulationEngine engine = engine(id);
        RealTimeDriver existing = drivers.get(id);
        if (existing != null) {
            existing.setSpeed(factor);
            return;
        }
        RealTimeDriver driver = new RealTimeDriver(engine, factor);
        drivers.put(id, driver);
        driver.start();
    }

    @Override
    public void stopRealTime(UUID id) {
        engine(id);
        RealTimeDriver driver = drivers.remove(id);
        if (driver != null) {
            driver.close();
        }
    }

    // ---- trace ----

    @Override
    public List<SimulationEvent> events(UUID id, int from) {
        return engine(id).events(from);
    }

    @Override
    public void addListener(SimulationListener listener) {
        listeners.add(listener);
    }

    @Override
    public void close() {
        drivers.values().forEach(RealTimeDriver::close);
        drivers.clear();
        engines.values().forEach(SimulationEngine::close);
        engines.clear();
    }

    private void notifyListeners(UUID id, String layoutId, SimulationEvent event) {
        for (SimulationListener listener : listeners) {
            listener.onEvent(id, layoutId, event);
        }
    }

    private SimulationEngine engine(UUID id) {
        SimulationEngine engine = engines.get(id);
        if (engine == null) {
            throw new NotFoundException("Unknown simulation: " + id);
        }
        return engine;
    }
}
