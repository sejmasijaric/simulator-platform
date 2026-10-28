package ch.hsg.simulation.api;

import ch.hsg.simulation.execution.ComponentAction;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes an operation a component supports, independent of REST/MQTT naming. All declared
 * parameters are required and no undeclared parameters are allowed. Physical semantics live in
 * the concrete component.
 */
public record Capability(String name, Map<String, ParameterType> parameters) {

    public Capability {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Capability name must not be blank");
        }
        parameters = Map.copyOf(parameters);
    }

    public static Capability of(String name) {
        return new Capability(name, Map.of());
    }

    public static Capability of(String name, Map<String, ParameterType> parameters) {
        return new Capability(name, parameters);
    }

    public boolean supports(ComponentAction action) {
        return name.equals(action.capability()) && validate(action).isEmpty();
    }

    /** Returns a description of the first problem with the action's parameters, if any. */
    public Optional<String> validate(ComponentAction action) {
        for (Map.Entry<String, ParameterType> expected : parameters.entrySet()) {
            Object value = action.parameters().get(expected.getKey());
            if (value == null) {
                return Optional.of("Missing parameter '" + expected.getKey() + "' for capability '" + name + "'");
            }
            if (!expected.getValue().accepts(value)) {
                return Optional.of("Parameter '" + expected.getKey() + "' must be of type " + expected.getValue());
            }
        }
        for (String given : action.parameters().keySet()) {
            if (!parameters.containsKey(given)) {
                return Optional.of("Unknown parameter '" + given + "' for capability '" + name + "'");
            }
        }
        return Optional.empty();
    }
}
