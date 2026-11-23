package ch.hsg.simulation.core.net;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A coloured token: a workpiece with attributes (e.g. {@code color}), a request in a command
 * place (its attributes are the request's parameters) or a plain resource token. {@code commandId}
 * is the command whose firings last moved it, so a call can wait for its own work to finish.
 */
public record Token(String id, Map<String, Object> attributes, UUID commandId) {

    public Token {
        Objects.requireNonNull(id, "id");
        attributes = attributes == null || attributes.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public Token withCommand(UUID command) {
        return Objects.equals(command, commandId) ? this : new Token(id, attributes, command);
    }

    public Object attribute(String name) {
        return attributes.get(name);
    }
}
