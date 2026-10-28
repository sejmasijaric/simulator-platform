package ch.hsg.simulation.model;

import java.util.Map;
import java.util.Optional;

/**
 * Observable, immutable state of a component. Each concrete component documents its own
 * attribute schema; the core treats the attributes as opaque values.
 */
public record ComponentState(Map<String, Object> attributes) {

    private static final ComponentState EMPTY = new ComponentState(Map.of());

    public ComponentState {
        attributes = Attributes.copyOf(attributes);
    }

    public static ComponentState empty() {
        return EMPTY;
    }

    public static ComponentState of(Map<String, ?> attributes) {
        return new ComponentState(Attributes.copyOf(attributes));
    }

    public Optional<Object> get(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    public ComponentState with(String key, Object value) {
        return new ComponentState(Attributes.merge(attributes, java.util.Collections.singletonMap(key, value)));
    }

    public ComponentState with(Map<String, ?> updates) {
        return new ComponentState(Attributes.merge(attributes, updates));
    }
}
