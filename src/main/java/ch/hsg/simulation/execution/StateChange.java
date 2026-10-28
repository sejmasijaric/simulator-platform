package ch.hsg.simulation.execution;

import ch.hsg.simulation.model.Attributes;
import ch.hsg.simulation.model.ComponentState;

import java.util.Map;
import java.util.Objects;

/**
 * A change proposed by a component. The engine validates and commits a batch of changes
 * atomically: either all of them are applied or none.
 */
public sealed interface StateChange {

    record CreateEntity(String entityId, String type, Map<String, Object> attributes, String locationComponentId)
            implements StateChange {
        public CreateEntity {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(type, "type");
            attributes = Attributes.copyOf(attributes);
        }
    }

    record RemoveEntity(String entityId) implements StateChange {
        public RemoveEntity {
            Objects.requireNonNull(entityId, "entityId");
        }
    }

    record MoveEntity(String entityId, String targetComponentId) implements StateChange {
        public MoveEntity {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(targetComponentId, "targetComponentId");
        }
    }

    record UpdateEntityAttributes(String entityId, Map<String, Object> attributes) implements StateChange {
        public UpdateEntityAttributes {
            Objects.requireNonNull(entityId, "entityId");
            attributes = Attributes.copyOf(attributes);
        }
    }

    record UpdateComponentState(String componentId, ComponentState state) implements StateChange {
        public UpdateComponentState {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(state, "state");
        }
    }

    static StateChange move(String entityId, String targetComponentId) {
        return new MoveEntity(entityId, targetComponentId);
    }

    static StateChange componentState(String componentId, ComponentState state) {
        return new UpdateComponentState(componentId, state);
    }
}
