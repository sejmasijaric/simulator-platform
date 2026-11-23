package ch.hsg.simulation.core.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Things due at a future simulated time (the end of a firing), ordered by time, then by the
 * order they were scheduled, so equal times resolve deterministically. Engine loop only.
 */
public final class EventQueue<T> {

    public record Entry<T>(Duration time, long sequence, T item) {
    }

    private final PriorityQueue<Entry<T>> entries = new PriorityQueue<>(
            Comparator.<Entry<T>, Duration>comparing(Entry::time).thenComparingLong(Entry::sequence));
    private long nextSequence;

    public void schedule(Duration now, Duration time, T item) {
        Objects.requireNonNull(time, "time");
        if (time.compareTo(now) < 0) {
            throw new IllegalArgumentException("Cannot schedule in the past: " + time + " < " + now);
        }
        entries.add(new Entry<>(time, nextSequence++, item));
    }

    public Optional<Entry<T>> peek() {
        return Optional.ofNullable(entries.peek());
    }

    public Optional<Entry<T>> poll() {
        return Optional.ofNullable(entries.poll());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** All entries in due order. */
    public List<Entry<T>> entries() {
        List<Entry<T>> sorted = new ArrayList<>(entries);
        sorted.sort(entries.comparator());
        return sorted;
    }

    public void clear() {
        entries.clear();
        nextSequence = 0;
    }
}
