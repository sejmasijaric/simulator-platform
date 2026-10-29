package ch.hsg.simulation.model;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable, consistent view of one simulation at one point in simulated time. */
public record SimulationSnapshot(
        UUID id,
        String definitionId,
        String name,
        SimulationStatus status,
        Duration time,
        List<ComponentView> components,
        List<Connection> connections,
        List<EntityView> entities) {

    public SimulationSnapshot {
        components = List.copyOf(components);
        connections = List.copyOf(connections);
        entities = List.copyOf(entities);
    }

    public Optional<ComponentView> component(String componentId) {
        return components.stream().filter(component -> component.id().equals(componentId)).findFirst();
    }

    public Optional<EntityView> entity(String entityId) {
        return entities.stream().filter(entity -> entity.id().equals(entityId)).findFirst();
    }

    public List<EntityView> entitiesAt(String componentId) {
        return entities.stream().filter(entity -> componentId.equals(entity.locationComponentId())).toList();
    }
}
