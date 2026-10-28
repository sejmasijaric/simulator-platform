package ch.hsg.simulation.execution;

import ch.hsg.simulation.event.SimulationEvent;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a component action. It describes what should happen and when; the engine
 * schedules and commits it. Components never apply changes themselves.
 */
public record ComponentExecutionResult(
        ExecutionStatus status,
        List<ScheduledChange> phases,
        Optional<String> errorMessage) {

    public ComponentExecutionResult {
        Objects.requireNonNull(status, "status");
        phases = phases.stream().sorted(Comparator.comparing(ScheduledChange::offset)).toList();
        errorMessage = errorMessage != null ? errorMessage : Optional.empty();
        if ((status == ExecutionStatus.REJECTED || status == ExecutionStatus.FAILED) && !phases.isEmpty()) {
            throw new IllegalArgumentException(status + " results must not carry changes");
        }
    }

    /** Instant action: everything is committed at the current simulated time. */
    public static ComponentExecutionResult completed(List<StateChange> changes, List<SimulationEvent> events) {
        return new ComponentExecutionResult(ExecutionStatus.COMPLETED,
                List.of(new ScheduledChange(Duration.ZERO, changes, events)), Optional.empty());
    }

    /** Timed action: phases are committed at their offsets; the action completes after the last one. */
    public static ComponentExecutionResult accepted(List<ScheduledChange> phases) {
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("An accepted action needs at least one phase");
        }
        return new ComponentExecutionResult(ExecutionStatus.ACCEPTED, phases, Optional.empty());
    }

    public static ComponentExecutionResult accepted(ScheduledChange... phases) {
        return accepted(List.of(phases));
    }

    public static ComponentExecutionResult rejected(String reason) {
        return new ComponentExecutionResult(ExecutionStatus.REJECTED, List.of(), Optional.of(reason));
    }

    public static ComponentExecutionResult failed(String reason) {
        return new ComponentExecutionResult(ExecutionStatus.FAILED, List.of(), Optional.of(reason));
    }

    /** Simulated time from start until the action completes. */
    public Duration duration() {
        return phases.isEmpty() ? Duration.ZERO : phases.get(phases.size() - 1).offset();
    }

    public boolean isExecuted() {
        return status == ExecutionStatus.ACCEPTED || status == ExecutionStatus.COMPLETED;
    }
}
