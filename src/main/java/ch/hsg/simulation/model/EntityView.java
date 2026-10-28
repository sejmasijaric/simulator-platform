package ch.hsg.simulation.model;

import java.util.Map;
import java.util.Optional;

/** Read-only view of an entity, safe to hand to components and adapters. */
public record EntityView(String id, String type, Map<String, Object> attributes, String locationComponentId) {

    public EntityView {
        attributes = Attributes.copyOf(attributes);
    }

    public Optional<String> location() {
        return Optional.ofNullable(locationComponentId);
    }
}
