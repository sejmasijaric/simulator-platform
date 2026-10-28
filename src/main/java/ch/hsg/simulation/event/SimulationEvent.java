package ch.hsg.simulation.event;

import ch.hsg.simulation.model.Attributes;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Something that happened inside one simulation. Components create drafts without a timestamp
 * via {@link #draft}; the engine stamps the simulated time and command correlation on commit.
 */
public record SimulationEvent(
        UUID id,
        Duration timestamp,
        String sourceComponentId,
        EventType type,
        UUID commandId,
        Map<String, Object> payload) {

    public SimulationEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        payload = Attributes.copyOf(payload);
    }

    public static SimulationEvent draft(EventType type, String sourceComponentId, Map<String, ?> payload) {
        return new SimulationEvent(UUID.randomUUID(), null, sourceComponentId, type, null, Attributes.copyOf(payload));
    }

    public SimulationEvent stamped(Duration time, UUID correlationId) {
        return new SimulationEvent(id, time, sourceComponentId, type,
                commandId != null ? commandId : correlationId, payload);
    }
}
