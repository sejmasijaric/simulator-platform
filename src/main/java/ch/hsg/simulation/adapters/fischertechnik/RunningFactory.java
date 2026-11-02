package ch.hsg.simulation.adapters.fischertechnik;

import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.model.SimulationStatus;
import ch.hsg.simulation.runtime.SimulationManager;
import ch.hsg.simulation.systems.fischertechnik.FischertechnikLayoutDefinition;

import java.util.List;
import java.util.UUID;

/** The adapters serve the running Fischertechnik simulation; with none or several they do nothing. */
final class RunningFactory {

    private RunningFactory() {
    }

    static List<UUID> find(SimulationManager manager) {
        return manager.getSimulations().stream()
                .filter(snapshot -> snapshot.definitionId().equals(FischertechnikLayoutDefinition.ID))
                .filter(snapshot -> snapshot.status() == SimulationStatus.RUNNING)
                .map(SimulationSnapshot::id)
                .toList();
    }
}
