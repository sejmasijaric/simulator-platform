package ch.hsg.simulation.api;

import ch.hsg.simulation.model.Simulation;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Java-coded blueprint of one physical layout. Holds no runtime state; every call to
 * {@link #create} must build a fresh, independent simulation.
 */
public interface SimulationDefinition {

    /** Stable unique id, e.g. {@code fischertechnik-factory-4}. */
    String getId();

    String getName();

    String getDescription();

    default Optional<URI> getLayoutImage() {
        return Optional.empty();
    }

    /**
     * Builds a new simulation in status CREATED with its components, connections and initial
     * entities. The id is assigned by the manager so that a reset can keep it.
     */
    Simulation create(UUID simulationId);
}
