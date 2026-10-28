package ch.hsg.simulation.runtime;

import java.time.Duration;
import java.util.Objects;

/** Monotonic logical clock. It never reads system time and never sleeps. */
public final class SimulationClock {

    private Duration currentTime = Duration.ZERO;

    public Duration now() {
        return currentTime;
    }

    public void advance(Duration delta) {
        Objects.requireNonNull(delta, "delta");
        if (delta.isNegative()) {
            throw new IllegalArgumentException("Time cannot go backwards: " + delta);
        }
        currentTime = currentTime.plus(delta);
    }

    public void advanceTo(Duration target) {
        Objects.requireNonNull(target, "target");
        if (target.compareTo(currentTime) < 0) {
            throw new IllegalArgumentException("Time cannot go backwards: " + target + " < " + currentTime);
        }
        currentTime = target;
    }

    /** Only as part of resetting the whole simulation. */
    void reset() {
        currentTime = Duration.ZERO;
    }
}
