package ch.hsg.simulation.runtime;

import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.model.NotFoundException;
import ch.hsg.simulation.model.SimulationStateException;
import ch.hsg.simulation.model.SimulationStatus;
import ch.hsg.simulation.testlayout.TestLayoutDefinition;
import ch.hsg.simulation.testlayout.Transporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationManagerTest {

    private final SimulationManager manager = new SimulationManager(List.of(new TestLayoutDefinition()));

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private static ComponentAction transport() {
        return ComponentAction.of(Transporter.TRANSPORT, Map.of("from", "IN", "to", "OUT"));
    }

    @Test
    void createsIndependentInstances() {
        UUID first = manager.create(TestLayoutDefinition.ID);
        UUID second = manager.create(TestLayoutDefinition.ID);
        manager.start(first);
        manager.start(second);

        manager.execute(first, "T", transport());
        manager.engine(first).runUntilIdle();

        assertThat(first).isNotEqualTo(second);
        assertThat(manager.getSimulation(first).entity("wp-1").orElseThrow().locationComponentId()).isEqualTo("OUT");
        assertThat(manager.getSimulation(second).entity("wp-1").orElseThrow().locationComponentId()).isEqualTo("IN");
        assertThat(manager.getSimulations()).hasSize(2);
    }

    @Test
    void unknownDefinitionOrSimulationIsNotFound() {
        assertThatThrownBy(() -> manager.create("missing")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> manager.getSimulation(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void resetRebuildsFromDefinitionKeepingTheId() {
        UUID id = manager.create(TestLayoutDefinition.ID);
        manager.start(id);
        manager.execute(id, "T", transport());
        manager.engine(id).runUntilIdle();
        manager.setFault(id, "T", Transporter.ITEM_NOT_MOVED, true);

        manager.reset(id);

        var snapshot = manager.getSimulation(id);
        assertThat(snapshot.id()).isEqualTo(id);
        assertThat(snapshot.status()).isEqualTo(SimulationStatus.CREATED);
        assertThat(snapshot.time()).isZero();
        assertThat(snapshot.entity("wp-1").orElseThrow().locationComponentId()).isEqualTo("IN");
        assertThat(snapshot.component("T").orElseThrow().activeFaults()).isEmpty();
    }

    @Test
    void removeRequiresStoppedSimulation() {
        UUID id = manager.create(TestLayoutDefinition.ID);
        manager.start(id);

        assertThatThrownBy(() -> manager.remove(id)).isInstanceOf(SimulationStateException.class);

        manager.stop(id);
        manager.remove(id);
        assertThat(manager.getSimulations()).isEmpty();
    }

    @Test
    void duplicateDefinitionIdsAreRejected() {
        assertThatThrownBy(() -> new SimulationManager(List.of(new TestLayoutDefinition(), new TestLayoutDefinition())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
