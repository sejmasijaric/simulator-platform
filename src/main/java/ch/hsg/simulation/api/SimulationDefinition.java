package ch.hsg.simulation.api;

import ch.hsg.simulation.model.Simulation;

import java.net.URI;
import java.util.List;
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

    /** Optional drawing hints for the UI. */
    default Optional<LayoutView> getLayoutView() {
        return Optional.empty();
    }

    /** Entities a user may place during setup. */
    default List<EntityTemplate> getEntityTemplates() {
        return List.of();
    }

    /**
     * Builds a new simulation in status CREATED with its components, connections and initial
     * entities. The id is assigned by the manager so that a reset can keep it.
     */
    Simulation create(UUID simulationId);
}
