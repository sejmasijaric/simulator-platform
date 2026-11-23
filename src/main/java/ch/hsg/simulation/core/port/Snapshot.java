package ch.hsg.simulation.core.port;

import ch.hsg.simulation.core.fault.NetFault;
import ch.hsg.simulation.core.fault.SensorFault;
import ch.hsg.simulation.core.net.Token;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The state of one simulation at one moment, for the UI and tests. {@code running} are firings
 * whose tokens are inside a transition (e.g. a workpiece being burnt); {@code sensors} are the
 * values an application reads now, faults applied; {@code speed} is null without real time.
 */
public record Snapshot(
        UUID id,
        String layoutId,
        SimulationStatus status,
        Duration time,
        Map<String, List<Token>> marking,
        List<RunningFiring> running,
        Map<String, Object> sensors,
        Map<String, SensorFault> sensorFaults,
        Map<String, NetFault> netFaults,
        Set<String> activeModules,
        Double speed) {

    /** A transition that has taken its tokens and gives them back at {@code ends}. */
    public record RunningFiring(String transition, List<Token> tokens, UUID commandId, Duration started,
                                Duration ends) {
    }

    public List<Token> tokensAt(String place) {
        return marking.getOrDefault(place, List.of());
    }
}
