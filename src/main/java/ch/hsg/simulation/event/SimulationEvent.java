package ch.hsg.simulation.event;

import ch.hsg.simulation.model.Attributes;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Something that happened inside one simulation. Components create drafts without a timestamp
 * via {@link #draft}; the engine stamps simulated time, command id and origin on commit.
 * {@code origin} is who caused the event, e.g. {@code application} or {@code control}.
 */
public record SimulationEvent(
        UUID id,
        Duration timestamp,
        String sourceComponentId,
        EventType type,
        UUID commandId,
        String origin,
        Map<String, Object> payload) {

    public SimulationEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        payload = Attributes.copyOf(payload);
    }

    public static SimulationEvent draft(EventType type, String sourceComponentId, Map<String, ?> payload) {
        return new SimulationEvent(UUID.randomUUID(), null, sourceComponentId, type, null, null, Attributes.copyOf(payload));
    }

    public SimulationEvent stamped(Duration time, UUID correlationId, String causedBy) {
        return new SimulationEvent(id, time, sourceComponentId, type,
                commandId != null ? commandId : correlationId,
                origin != null ? origin : causedBy,
                payload);
    }
}
