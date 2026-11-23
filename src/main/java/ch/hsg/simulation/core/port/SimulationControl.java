package ch.hsg.simulation.core.port;

import ch.hsg.simulation.core.event.SimulationEvent;
import ch.hsg.simulation.core.fault.NetFault;
import ch.hsg.simulation.core.fault.SensorFault;
import ch.hsg.simulation.core.net.NetDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Driving port: everything the outside may ask of the core. Used by the REST control API (setup,
 * faults, speed) and by the generic HTTP adapter (commands and queries of a layout's own API).
 * Unknown ids raise {@code NotFoundException}; a wrong lifecycle state raises
 * {@code SimulationException}; invalid arguments raise {@link IllegalArgumentException}.
 */
public interface SimulationControl {

    // ---- layouts and lifecycle ----

    List<NetDefinition> layouts();

    NetDefinition layout(String layoutId);

    UUID create(String layoutId);

    void start(UUID id);

    /** Pause: firings in progress continue after the next start. */
    void stop(UUID id);

    /** Back to the initial marking, time zero, no faults, empty trace; same id. */
    void reset(UUID id);

    void remove(UUID id);

    Snapshot snapshot(UUID id);

    List<Snapshot> snapshots();

    /** The most recently started running simulation of a layout, for adapters bound to a layout. */
    Optional<UUID> running(String layoutId);

    // ---- the layout's own interface ----

    /**
     * Puts a request token with {@code parameters} into {@code commandPlace}. The handle completes
     * when {@code returnsAfter} fires for this request; with {@code returnsAfter} null it is
     * complete at once.
     */
    CommandHandle command(UUID id, String commandPlace, Map<String, ?> parameters, String returnsAfter, String origin);

    /** Every sensor's value as an application reads it now, faults applied. */
    Map<String, Object> readSensors(UUID id);

    // ---- preparing and disturbing a run ----

    /** Places a token by hand, e.g. a workpiece; returns its id (generated if {@code tokenId} is null). */
    String placeToken(UUID id, String place, String tokenId, Map<String, ?> attributes);

    void removeToken(UUID id, String tokenId);

    /** {@code fault} null clears it. */
    void setSensorFault(UUID id, String sensor, SensorFault fault);

    /** {@code fault} null clears it. */
    void setNetFault(UUID id, String transition, NetFault fault);

    /** Only transitions of these modules (and those without a module) fire; empty = all. */
    void setActiveModules(UUID id, Set<String> modules);

    // ---- time ----

    /** Ends the next firing in progress, jumping time to it. False if nothing is in progress. */
    boolean step(UUID id);

    void runUntil(UUID id, Duration time);

    /** Moves time with the wall clock, {@code factor} times as fast. */
    void setSpeed(UUID id, double factor);

    /** Back to manual time (step, runUntil). */
    void stopRealTime(UUID id);

    // ---- trace ----

    List<SimulationEvent> events(UUID id, int from);

    void addListener(SimulationListener listener);
}
