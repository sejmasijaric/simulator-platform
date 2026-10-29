package ch.hsg.simulation.model;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** An accepted action that has not completed yet. */
public record ComponentActivity(
        UUID commandId,
        String capability,
        Map<String, Object> parameters,
        String origin,
        Duration startedAt,
        Duration endsAt) {

    public ComponentActivity {
        parameters = Attributes.copyOf(parameters);
    }
}
