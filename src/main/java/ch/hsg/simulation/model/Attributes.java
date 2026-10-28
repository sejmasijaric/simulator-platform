package ch.hsg.simulation.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Helper for immutable, insertion-ordered attribute maps that may contain null values. */
public final class Attributes {

    private Attributes() {
    }

    public static Map<String, Object> copyOf(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static Map<String, Object> merge(Map<String, ?> base, Map<String, ?> updates) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(updates);
        return Collections.unmodifiableMap(merged);
    }
}
