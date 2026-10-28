package ch.hsg.simulation.runtime;

import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.testlayout.TestLayoutDefinition;
import ch.hsg.simulation.testlayout.Transporter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RealTimeDriverTest {

    @Test
    void advancesSimulatedTimeWithTheWallClock() throws Exception {
        try (SimulationEngine engine = new SimulationEngine(new TestLayoutDefinition().create(UUID.randomUUID()));
             RealTimeDriver driver = new RealTimeDriver(engine, 20.0, Duration.ofMillis(10))) {
            engine.start();
            driver.start();
            ComponentAction action = ComponentAction.of(Transporter.TRANSPORT, Map.of("from", "IN", "to", "OUT"));

            engine.execute("T", action);
            engine.completionOf(action.commandId()).get(2, TimeUnit.SECONDS);

            assertThat(engine.snapshot().entity("wp-1").orElseThrow().locationComponentId()).isEqualTo("OUT");
            assertThat(engine.now()).isGreaterThanOrEqualTo(Duration.ofSeconds(4));
        }
    }
}
