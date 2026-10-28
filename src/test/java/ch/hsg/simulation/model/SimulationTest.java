package ch.hsg.simulation.model;

import ch.hsg.simulation.execution.StateChange;
import ch.hsg.simulation.testlayout.TestLayoutDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationTest {

    private final Simulation simulation = new TestLayoutDefinition().create(UUID.randomUUID());

    @Test
    void batchIsAtomic() {
        assertThatThrownBy(() -> simulation.apply(List.of(
                StateChange.move("wp-1", "HOLD"),
                StateChange.move("wp-1", "X"))))
                .isInstanceOf(InvalidStateChangeException.class);

        assertThat(simulation.findEntity("wp-1").orElseThrow().locationComponentId()).isEqualTo("IN");
    }

    @Test
    void capacityIsEnforced() {
        simulation.apply(List.of(new StateChange.CreateEntity("wp-2", "workpiece", Map.of(), "HOLD")));

        assertThatThrownBy(() -> simulation.apply(List.of(StateChange.move("wp-1", "HOLD"))))
                .isInstanceOf(InvalidStateChangeException.class)
                .hasMessageContaining("full");
    }

    @Test
    void unknownTargetIsRejected() {
        assertThatThrownBy(() -> simulation.apply(List.of(StateChange.move("wp-1", "nowhere"))))
                .isInstanceOf(InvalidStateChangeException.class);
    }

    @Test
    void sameDefinitionCreatesIndependentModels() {
        Simulation other = new TestLayoutDefinition().create(UUID.randomUUID());
        simulation.apply(List.of(StateChange.move("wp-1", "HOLD")));

        assertThat(other.findEntity("wp-1").orElseThrow().locationComponentId()).isEqualTo("IN");
    }

    @Test
    void assemblyIsClosedOnceSealed() {
        simulation.seal();
        assertThatThrownBy(() -> simulation.connect("IN", "X")).isInstanceOf(SimulationStateException.class);
    }

    @Test
    void validationCatchesDanglingReferences() {
        Simulation broken = new Simulation(UUID.randomUUID(), "broken", "Broken")
                .addComponent(new ch.hsg.simulation.testlayout.Slot("A"))
                .connect("A", "ghost");
        assertThatThrownBy(broken::validate).isInstanceOf(IllegalStateException.class);
    }
}
