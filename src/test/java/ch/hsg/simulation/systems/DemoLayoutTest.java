package ch.hsg.simulation.systems;

import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.runtime.SimulationEngine;
import ch.hsg.simulation.systems.common.Conveyor;
import ch.hsg.simulation.systems.demo.DemoLayoutDefinition;
import ch.hsg.simulation.systems.demo.Transporter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DemoLayoutTest {

    @Test
    void workpieceTravelsFromInToEnd() {
        try (SimulationEngine engine = new SimulationEngine(new DemoLayoutDefinition().create(UUID.randomUUID()))) {
            engine.start();

            engine.execute("T", ComponentAction.of(Transporter.TRANSPORT, Map.of("from", "IN", "to", "OUT")));
            engine.runUntilIdle();
            engine.execute("C", ComponentAction.of(Conveyor.RUN));
            engine.runUntilIdle();

            var snapshot = engine.snapshot();
            assertThat(snapshot.entity("workpiece-red").orElseThrow().locationComponentId()).isEqualTo("END");
            assertThat(snapshot.component("LB_END").orElseThrow().state().attributes()).containsEntry("interrupted", true);
            assertThat(snapshot.time()).isEqualTo(Duration.ofSeconds(10));
        }
    }

    @Test
    void layoutViewCoversAllComponents() {
        DemoLayoutDefinition definition = new DemoLayoutDefinition();
        var positions = definition.getLayoutView().orElseThrow().positions();
        definition.create(UUID.randomUUID()).getComponents()
                .forEach(component -> assertThat(positions).containsKey(component.getId()));
    }
}
