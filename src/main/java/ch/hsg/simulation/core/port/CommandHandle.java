package ch.hsg.simulation.core.port;

import ch.hsg.simulation.core.event.SimulationEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A request accepted into a command place. {@code completion} completes with the
 * COMMAND_COMPLETED event once the transition the request waits for has fired for it.
 */
public record CommandHandle(UUID commandId, CompletableFuture<SimulationEvent> completion) {
}
