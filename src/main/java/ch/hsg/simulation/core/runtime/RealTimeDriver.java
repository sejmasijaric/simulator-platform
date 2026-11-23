package ch.hsg.simulation.core.runtime;

import ch.hsg.simulation.core.port.SimulationStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Moves a simulation's time with the wall clock: every tick it advances simulated time by the
 * elapsed real time times {@code speed}. Needed whenever an unchanged application talks to the
 * simulator as if it were the physical layout. While the simulation is stopped it waits.
 */
public final class RealTimeDriver implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RealTimeDriver.class.getName());
    private static final Duration TICK = Duration.ofMillis(20);

    private final SimulationEngine engine;
    private final ScheduledExecutorService scheduler;

    private volatile double speed;
    private volatile boolean rebase = true;
    private long baseWallNanos;
    private Duration baseSimulationTime;

    public RealTimeDriver(SimulationEngine engine, double speed) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.speed = requirePositive(speed);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "real-time-" + engine.id());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, 0, TICK.toNanos(), TimeUnit.NANOSECONDS);
    }

    public double speed() {
        return speed;
    }

    public void setSpeed(double factor) {
        speed = requirePositive(factor);
        rebase = true;
    }

    private void tick() {
        try {
            if (engine.status() != SimulationStatus.RUNNING) {
                rebase = true;
                return;
            }
            if (rebase) {
                baseWallNanos = System.nanoTime();
                baseSimulationTime = engine.now();
                rebase = false;
            }
            long elapsed = System.nanoTime() - baseWallNanos;
            engine.runUntil(baseSimulationTime.plusNanos((long) (elapsed * speed)));
        } catch (RuntimeException exception) {
            rebase = true;
            LOG.log(Level.FINE, "Real-time tick skipped", exception);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static double requirePositive(double factor) {
        if (!(factor > 0)) {
            throw new IllegalArgumentException("Speed must be positive");
        }
        return factor;
    }
}
