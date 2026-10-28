package ch.hsg.simulation.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A workpiece or other movable item tracked within one simulation. Its location is the id of
 * the component it currently sits at, or absent when it is outside the modeled system.
 */
public final class Entity {

    private final String id;
    private final String type;
    private Map<String, Object> attributes;
    private String locationComponentId;

    public Entity(String id, String type, Map<String, ?> attributes, String locationComponentId) {
        this.id = requireText(id, "Entity id");
        this.type = requireText(type, "Entity type");
        this.attributes = Attributes.copyOf(attributes);
        this.locationComponentId = locationComponentId;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Optional<String> getLocationComponentId() {
        return Optional.ofNullable(locationComponentId);
    }

    public EntityView view() {
        return new EntityView(id, type, attributes, locationComponentId);
    }

    void moveTo(String componentId) {
        this.locationComponentId = componentId;
    }

    void updateAttributes(Map<String, ?> updates) {
        this.attributes = Attributes.merge(attributes, updates);
    }

    Entity copy() {
        return new Entity(id, type, attributes, locationComponentId);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
