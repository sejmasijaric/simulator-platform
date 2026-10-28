package ch.hsg.simulation.app;

import ch.hsg.simulation.runtime.RealTimeDriver;
import ch.hsg.simulation.runtime.SimulationManager;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Starts and stops one {@link RealTimeDriver} per simulation. */
@Service
public class RealTimeService {

    private static final Duration TICK = Duration.ofMillis(20);

    private final SimulationManager manager;
    private final Map<UUID, Driver> drivers = new ConcurrentHashMap<>();

    public RealTimeService(SimulationManager manager) {
        this.manager = manager;
    }

    public void enable(UUID simulationId, double speedFactor) {
        RealTimeDriver driver = new RealTimeDriver(manager.engine(simulationId), speedFactor, TICK);
        disable(simulationId);
        drivers.put(simulationId, new Driver(driver, speedFactor));
        driver.start();
    }

    public void disable(UUID simulationId) {
        Driver previous = drivers.remove(simulationId);
        if (previous != null) {
            previous.driver().close();
        }
    }

    public Optional<Double> speedFactor(UUID simulationId) {
        return Optional.ofNullable(drivers.get(simulationId)).map(Driver::speedFactor);
    }

    @PreDestroy
    void shutdown() {
        drivers.values().forEach(driver -> driver.driver().close());
        drivers.clear();
    }

    private record Driver(RealTimeDriver driver, double speedFactor) {
    }
}
