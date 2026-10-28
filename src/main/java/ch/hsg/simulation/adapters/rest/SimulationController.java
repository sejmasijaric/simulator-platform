package ch.hsg.simulation.adapters.rest;

import ch.hsg.simulation.adapters.rest.Dtos.ActionRequest;
import ch.hsg.simulation.adapters.rest.Dtos.ActionResponse;
import ch.hsg.simulation.adapters.rest.Dtos.CreateSimulationRequest;
import ch.hsg.simulation.adapters.rest.Dtos.CreatedResponse;
import ch.hsg.simulation.adapters.rest.Dtos.DefinitionResponse;
import ch.hsg.simulation.adapters.rest.Dtos.FaultRequest;
import ch.hsg.simulation.adapters.rest.Dtos.RealTimeRequest;
import ch.hsg.simulation.adapters.rest.Dtos.RealTimeResponse;
import ch.hsg.simulation.adapters.rest.Dtos.RunUntilRequest;
import ch.hsg.simulation.adapters.rest.Dtos.StepResponse;
import ch.hsg.simulation.app.RealTimeService;
import ch.hsg.simulation.event.SimulationEvent;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.runtime.SimulationEngine;
import ch.hsg.simulation.runtime.SimulationManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Control and test API. Applications use the layout's hardware-compatible adapter instead (ADR 0009). */
@RestController
public class SimulationController {

    private final SimulationManager manager;
    private final RealTimeService realTime;

    public SimulationController(SimulationManager manager, RealTimeService realTime) {
        this.manager = manager;
        this.realTime = realTime;
    }

    @GetMapping("/simulation-definitions")
    public List<DefinitionResponse> definitions() {
        return manager.getDefinitions().stream().map(DefinitionResponse::of).toList();
    }

    @PostMapping("/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse create(@RequestBody CreateSimulationRequest request) {
        if (request.definitionId() == null) {
            throw new IllegalArgumentException("definitionId is required");
        }
        return new CreatedResponse(manager.create(request.definitionId()));
    }

    @GetMapping("/simulations")
    public List<SimulationSnapshot> simulations() {
        return manager.getSimulations();
    }

    @GetMapping("/simulations/{id}")
    public SimulationSnapshot simulation(@PathVariable UUID id) {
        return manager.getSimulation(id);
    }

    @DeleteMapping("/simulations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id) {
        manager.remove(id);
        realTime.disable(id);
    }

    @PostMapping("/simulations/{id}/start")
    public SimulationSnapshot start(@PathVariable UUID id) {
        manager.start(id);
        return manager.getSimulation(id);
    }

    @PostMapping("/simulations/{id}/stop")
    public SimulationSnapshot stop(@PathVariable UUID id) {
        manager.stop(id);
        return manager.getSimulation(id);
    }

    @PostMapping("/simulations/{id}/reset")
    public SimulationSnapshot reset(@PathVariable UUID id) {
        manager.reset(id);
        return manager.getSimulation(id);
    }

    @PostMapping("/simulations/{id}/step")
    public StepResponse step(@PathVariable UUID id) {
        SimulationEngine engine = manager.engine(id);
        boolean stepped = engine.step();
        return new StepResponse(stepped, engine.now());
    }

    @PostMapping("/simulations/{id}/run-until")
    public SimulationSnapshot runUntil(@PathVariable UUID id, @RequestBody RunUntilRequest request) {
        if (request.time() == null) {
            throw new IllegalArgumentException("time is required, e.g. \"PT10S\"");
        }
        manager.engine(id).runUntil(request.time());
        return manager.getSimulation(id);
    }

    @PostMapping("/simulations/{id}/components/{componentId}/actions")
    public ResponseEntity<ActionResponse> execute(@PathVariable UUID id, @PathVariable String componentId,
                                                  @RequestBody ActionRequest request) {
        if (request.capability() == null) {
            throw new IllegalArgumentException("capability is required");
        }
        ComponentAction action = request.toAction();
        ComponentExecutionResult result = manager.execute(id, componentId, action);
        HttpStatus status = switch (result.status()) {
            case COMPLETED -> HttpStatus.OK;
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case REJECTED, FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(ActionResponse.of(action, result));
    }

    @PutMapping("/simulations/{id}/components/{componentId}/faults/{faultId}")
    public SimulationSnapshot setFault(@PathVariable UUID id, @PathVariable String componentId,
                                       @PathVariable String faultId, @RequestBody FaultRequest request) {
        manager.setFault(id, componentId, faultId, request.active());
        return manager.getSimulation(id);
    }

    @GetMapping("/simulations/{id}/events")
    public List<SimulationEvent> events(@PathVariable UUID id, @RequestParam(defaultValue = "0") int from) {
        List<SimulationEvent> events = manager.getEvents(id);
        return events.subList(Math.min(Math.max(from, 0), events.size()), events.size());
    }

    @PutMapping("/simulations/{id}/real-time")
    public RealTimeResponse enableRealTime(@PathVariable UUID id, @RequestBody RealTimeRequest request) {
        realTime.enable(id, request.speedFactor());
        return new RealTimeResponse(true, request.speedFactor());
    }

    @DeleteMapping("/simulations/{id}/real-time")
    public RealTimeResponse disableRealTime(@PathVariable UUID id) {
        manager.engine(id);
        realTime.disable(id);
        return new RealTimeResponse(false, null);
    }

    @GetMapping("/simulations/{id}/real-time")
    public RealTimeResponse realTime(@PathVariable UUID id) {
        manager.engine(id);
        Double speed = realTime.speedFactor(id).orElse(null);
        return new RealTimeResponse(speed != null, speed);
    }
}
