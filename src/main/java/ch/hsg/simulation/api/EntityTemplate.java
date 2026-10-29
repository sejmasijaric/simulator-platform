package ch.hsg.simulation.api;

import ch.hsg.simulation.model.Attributes;

import java.util.Map;
import java.util.Objects;

/** A kind of entity that can be placed into a simulation during setup, e.g. a red workpiece. */
public record EntityTemplate(String label, String type, Map<String, Object> attributes) {

    public EntityTemplate {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        attributes = Attributes.copyOf(attributes);
    }
}
