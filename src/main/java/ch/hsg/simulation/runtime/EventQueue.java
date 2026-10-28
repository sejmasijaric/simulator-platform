package ch.hsg.simulation.runtime;

import ch.hsg.simulation.event.SimulationEvent;
import ch.hsg.simulation.execution.StateChange;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Pending phases ordered by (time, insertion sequence) for deterministic tie-breaking. Not
 * thread-safe: only used from the engine's serialized execution loop.
 */
public final class EventQueue {

    /** A batch of changes and events to commit at {@code time}; the last phase of an action completes it. */
    public record ScheduledEntry(
            Duration time,
            long sequence,
            UUID commandId,
            String componentId,
            List<StateChange> changes,
            List<SimulationEvent> events,
            boolean completesAction) {
    }

    private final PriorityQueue<ScheduledEntry> entries = new PriorityQueue<>(
            Comparator.comparing(ScheduledEntry::time).thenComparingLong(ScheduledEntry::sequence));
    private long nextSequence;

    public ScheduledEntry schedule(Duration now, Duration time, UUID commandId, String componentId,
                                   List<StateChange> changes, List<SimulationEvent> events, boolean completesAction) {
        Objects.requireNonNull(time, "time");
        if (time.compareTo(now) < 0) {
            throw new IllegalArgumentException("Cannot schedule in the past: " + time + " < " + now);
        }
        ScheduledEntry entry = new ScheduledEntry(time, nextSequence++, commandId, componentId,
                List.copyOf(changes), List.copyOf(events), completesAction);
        entries.add(entry);
        return entry;
    }

    public Optional<ScheduledEntry> peek() {
        return Optional.ofNullable(entries.peek());
    }

    public Optional<ScheduledEntry> poll() {
        return Optional.ofNullable(entries.poll());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        nextSequence = 0;
    }
}
