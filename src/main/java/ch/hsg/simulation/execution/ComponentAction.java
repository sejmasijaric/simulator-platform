package ch.hsg.simulation.execution;

import ch.hsg.simulation.model.Attributes;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable request to execute one capability of a component. {@code origin} names who sent it,
 * e.g. {@link #ORIGIN_APPLICATION} for commands arriving through a hardware-compatible adapter.
 */
public record ComponentAction(String capability, Map<String, Object> parameters, UUID commandId, String origin) {

    public static final String ORIGIN_APPLICATION = "application";
    public static final String ORIGIN_CONTROL = "control";

    public ComponentAction {
        Objects.requireNonNull(capability, "capability");
        parameters = Attributes.copyOf(parameters);
        commandId = commandId != null ? commandId : UUID.randomUUID();
        origin = origin != null && !origin.isBlank() ? origin : ORIGIN_CONTROL;
    }

    public static ComponentAction of(String capability) {
        return new ComponentAction(capability, Map.of(), null, null);
    }

    public static ComponentAction of(String capability, Map<String, ?> parameters) {
        return new ComponentAction(capability, Attributes.copyOf(parameters), null, null);
    }

    public ComponentAction withOrigin(String newOrigin) {
        return new ComponentAction(capability, parameters, commandId, newOrigin);
    }

    public Optional<Object> parameter(String name) {
        return Optional.ofNullable(parameters.get(name));
    }

    public String stringParameter(String name) {
        return (String) parameters.get(name);
    }
}
