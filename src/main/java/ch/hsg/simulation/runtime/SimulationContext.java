package ch.hsg.simulation.runtime;

import ch.hsg.simulation.model.ComponentState;
import ch.hsg.simulation.model.EntityView;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Narrow, read-only window into one simulation for component implementations. Components
 * request changes through their execution result; they never mutate state through the context.
 */
public interface SimulationContext {

    Duration now();

    Optional<EntityView> findEntity(String entityId);

    List<EntityView> entitiesAt(String componentId);

    boolean isConnected(String sourceId, String targetId);

    ComponentState stateOf(String componentId);

    /** True while an accepted action of this component has not completed yet. */
    boolean isBusy(String componentId);

    boolean isFaultActive(String componentId, String faultId);
}
