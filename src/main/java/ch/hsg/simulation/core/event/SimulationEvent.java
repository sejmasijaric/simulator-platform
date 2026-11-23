package ch.hsg.simulation.core.event;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Something that happened in one simulation, at simulated {@code time}. {@code subject} is the
 * transition, place, sensor or token it is about; {@code commandId} ties it to the request that
 * caused it, so a trace shows which firings one API call produced. {@code origin} is
 * {@code application} or {@code control}.
 */
public record SimulationEvent(long index, Duration time, EventType type, String subject, UUID commandId,
                              String origin, Map<String, Object> data) {

    public SimulationEvent {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(type, "type");
        data = data == null || data.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
