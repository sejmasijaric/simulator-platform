package ch.hsg.simulation.execution;

import ch.hsg.simulation.model.Attributes;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable request to execute one capability of a component. */
public record ComponentAction(String capability, Map<String, Object> parameters, UUID commandId) {

    public ComponentAction {
        Objects.requireNonNull(capability, "capability");
        parameters = Attributes.copyOf(parameters);
        commandId = commandId != null ? commandId : UUID.randomUUID();
    }

    public static ComponentAction of(String capability) {
        return new ComponentAction(capability, Map.of(), null);
    }

    public static ComponentAction of(String capability, Map<String, ?> parameters) {
        return new ComponentAction(capability, Attributes.copyOf(parameters), null);
    }

    public Optional<Object> parameter(String name) {
        return Optional.ofNullable(parameters.get(name));
    }

    public String stringParameter(String name) {
        return (String) parameters.get(name);
    }
}
