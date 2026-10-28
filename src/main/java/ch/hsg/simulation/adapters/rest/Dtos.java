package ch.hsg.simulation.adapters.rest;

import ch.hsg.simulation.api.SimulationDefinition;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.execution.ExecutionStatus;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies of the control API. */
final class Dtos {

    private Dtos() {
    }

    record DefinitionResponse(String id, String name, String description) {
        static DefinitionResponse of(SimulationDefinition definition) {
            return new DefinitionResponse(definition.getId(), definition.getName(), definition.getDescription());
        }
    }

    record CreateSimulationRequest(String definitionId) {
    }

    record CreatedResponse(UUID id) {
    }

    record ActionRequest(String capability, Map<String, Object> parameters) {
        ComponentAction toAction() {
            return ComponentAction.of(capability, parameters != null ? parameters : Map.of());
        }
    }

    record ActionResponse(UUID commandId, ExecutionStatus status, Duration duration, String errorMessage) {
        static ActionResponse of(ComponentAction action, ComponentExecutionResult result) {
            return new ActionResponse(action.commandId(), result.status(), result.duration(),
                    result.errorMessage().orElse(null));
        }
    }

    record FaultRequest(boolean active) {
    }

    record RunUntilRequest(Duration time) {
    }

    record StepResponse(boolean stepped, Duration time) {
    }

    record RealTimeRequest(double speedFactor) {
    }

    record RealTimeResponse(boolean enabled, Double speedFactor) {
    }

    record ErrorResponse(String error) {
    }
}
