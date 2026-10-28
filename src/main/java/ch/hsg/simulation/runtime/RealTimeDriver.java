package ch.hsg.simulation.runtime;

import ch.hsg.simulation.model.SimulationStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional outer adapter that paces a simulation with the wall clock: every tick it advances
 * simulated time by the elapsed real time multiplied by {@code speedFactor}. Needed whenever an
 * unmodified application talks to the simulator as if it were the physical layout. The core
 * itself never reads system time.
 */
public final class RealTimeDriver implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RealTimeDriver.class.getName());

    private final SimulationEngine engine;
    private final double speedFactor;
    private final Duration tick;
    private final ScheduledExecutorService scheduler;

    private boolean rebase = true;
    private long baseWallNanos;
    private Duration baseSimulationTime;

    public RealTimeDriver(SimulationEngine engine, double speedFactor, Duration tick) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (speedFactor <= 0) {
            throw new IllegalArgumentException("Speed factor must be positive");
        }
        if (tick.isZero() || tick.isNegative()) {
            throw new IllegalArgumentException("Tick must be positive");
        }
        this.speedFactor = speedFactor;
        this.tick = tick;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "real-time-driver-" + engine.getSimulationId());
            thread.setDaemon(true);
            return thread;
        });
    }

    public static RealTimeDriver realTime(SimulationEngine engine) {
        return new RealTimeDriver(engine, 1.0, Duration.ofMillis(20));
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, 0, tick.toNanos(), TimeUnit.NANOSECONDS);
    }

    void tick() {
        try {
            if (engine.getStatus() != SimulationStatus.RUNNING) {
                rebase = true;
                return;
            }
            if (rebase) {
                baseWallNanos = System.nanoTime();
                baseSimulationTime = engine.now();
                rebase = false;
            }
            long elapsed = System.nanoTime() - baseWallNanos;
            engine.runUntil(baseSimulationTime.plusNanos((long) (elapsed * speedFactor)));
        } catch (RuntimeException exception) {
            rebase = true;
            LOG.log(Level.FINE, "Real-time tick skipped", exception);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
