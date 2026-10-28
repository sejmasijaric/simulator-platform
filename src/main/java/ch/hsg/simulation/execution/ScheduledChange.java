package ch.hsg.simulation.execution;

import ch.hsg.simulation.event.SimulationEvent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One phase of an action: changes and events committed at {@code offset} after the action started.
 * Multi-phase actions (e.g. pick up, then drop off) return several phases.
 */
public record ScheduledChange(Duration offset, List<StateChange> changes, List<SimulationEvent> events) {

    public ScheduledChange {
        Objects.requireNonNull(offset, "offset");
        if (offset.isNegative()) {
            throw new IllegalArgumentException("Phase offset must not be negative: " + offset);
        }
        changes = List.copyOf(changes);
        events = List.copyOf(events);
    }

    public static ScheduledChange at(Duration offset, StateChange... changes) {
        return new ScheduledChange(offset, List.of(changes), List.of());
    }

    public static ScheduledChange at(Duration offset, List<StateChange> changes, List<SimulationEvent> events) {
        return new ScheduledChange(offset, changes, events);
    }
}
