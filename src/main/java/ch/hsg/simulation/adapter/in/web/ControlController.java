package ch.hsg.simulation.adapter.in.web;

import ch.hsg.simulation.core.event.SimulationEvent;
import ch.hsg.simulation.core.fault.NetFault;
import ch.hsg.simulation.core.fault.SensorFault;
import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.port.CommandHandle;
import ch.hsg.simulation.core.port.SimulationControl;
import ch.hsg.simulation.core.port.Snapshot;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Driving adapter: the simulator's own control API, for the web UI, tests and setup scripts
 * (ADR 0010). Applications use the layout's own interface instead ({@link LayoutHttpFilter}).
 * Durations are ISO-8601 ({@code PT4S}).
 */
@RestController
public class ControlController {

    public record CreateRequest(String layoutId) {
    }

    public record CreatedResponse(UUID id) {
    }

    public record RunUntilRequest(Duration time) {
    }

    public record StepResponse(boolean stepped, Duration time) {
    }

    public record SpeedRequest(double factor) {
    }

    public record TokenRequest(String place, String id, Map<String, Object> attributes) {
    }

    public record TokenResponse(String id) {
    }

    public record SensorFaultRequest(SensorFault.Type type, Object value, Duration delay) {
    }

    public record NetFaultRequest(NetFault.Type type, Double factor, String place) {
    }

    public record ModulesRequest(Set<String> modules) {
    }

    /** A request token put into a command place directly, to try out what it does in the net. */
    public record CommandRequest(String place, Map<String, Object> parameters, List<String> returnsAfter,
                                 Boolean await, Duration timeout) {
    }

    public record CommandResponse(UUID commandId, SimulationEvent completed) {
    }

    private final SimulationControl control;

    public ControlController(SimulationControl control) {
        this.control = control;
    }

    // ---- layouts ----

    @GetMapping("/layouts")
    List<NetDefinition> layouts() {
        return control.layouts();
    }

    @GetMapping("/layouts/{layoutId}")
    NetDefinition layout(@PathVariable("layoutId") String layoutId) {
        return control.layout(layoutId);
    }

    // ---- lifecycle ----

    @PostMapping("/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedResponse create(@RequestBody CreateRequest request) {
        return new CreatedResponse(control.create(required(request.layoutId(), "layoutId")));
    }

    @GetMapping("/simulations")
    List<Snapshot> snapshots() {
        return control.snapshots();
    }

    @GetMapping("/simulations/{id}")
    Snapshot snapshot(@PathVariable("id") UUID id) {
        return control.snapshot(id);
    }

    @DeleteMapping("/simulations/{id}")
    ResponseEntity<Void> remove(@PathVariable("id") UUID id) {
        control.remove(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/simulations/{id}/start")
    Snapshot start(@PathVariable("id") UUID id) {
        control.start(id);
        return control.snapshot(id);
    }

    @PostMapping("/simulations/{id}/stop")
    Snapshot stop(@PathVariable("id") UUID id) {
        control.stop(id);
        return control.snapshot(id);
    }

    @PostMapping("/simulations/{id}/reset")
    Snapshot reset(@PathVariable("id") UUID id) {
        control.reset(id);
        return control.snapshot(id);
    }

    // ---- time ----

    @PostMapping("/simulations/{id}/step")
    StepResponse step(@PathVariable("id") UUID id) {
        boolean stepped = control.step(id);
        return new StepResponse(stepped, control.snapshot(id).time());
    }

    @PostMapping("/simulations/{id}/run-until")
    Snapshot runUntil(@PathVariable("id") UUID id, @RequestBody RunUntilRequest request) {
        control.runUntil(id, required(request.time(), "time"));
        return control.snapshot(id);
    }

    @PutMapping("/simulations/{id}/speed")
    Snapshot setSpeed(@PathVariable("id") UUID id, @RequestBody SpeedRequest request) {
        control.setSpeed(id, request.factor());
        return control.snapshot(id);
    }

    @DeleteMapping("/simulations/{id}/speed")
    Snapshot stopRealTime(@PathVariable("id") UUID id) {
        control.stopRealTime(id);
        return control.snapshot(id);
    }

    // ---- preparing and disturbing a run ----

    @PostMapping("/simulations/{id}/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    TokenResponse placeToken(@PathVariable("id") UUID id, @RequestBody TokenRequest request) {
        return new TokenResponse(control.placeToken(id, required(request.place(), "place"), request.id(),
                request.attributes() == null ? Map.of() : request.attributes()));
    }

    @DeleteMapping("/simulations/{id}/tokens/{tokenId}")
    Snapshot removeToken(@PathVariable("id") UUID id, @PathVariable("tokenId") String tokenId) {
        control.removeToken(id, tokenId);
        return control.snapshot(id);
    }

    @PutMapping("/simulations/{id}/faults/sensors/{sensor}")
    Snapshot setSensorFault(@PathVariable("id") UUID id, @PathVariable("sensor") String sensor, @RequestBody SensorFaultRequest request) {
        control.setSensorFault(id, sensor,
                new SensorFault(required(request.type(), "type"), request.value(), request.delay()));
        return control.snapshot(id);
    }

    @DeleteMapping("/simulations/{id}/faults/sensors/{sensor}")
    Snapshot clearSensorFault(@PathVariable("id") UUID id, @PathVariable("sensor") String sensor) {
        control.setSensorFault(id, sensor, null);
        return control.snapshot(id);
    }

    @PutMapping("/simulations/{id}/faults/transitions/{transition}")
    Snapshot setNetFault(@PathVariable("id") UUID id, @PathVariable("transition") String transition, @RequestBody NetFaultRequest request) {
        control.setNetFault(id, transition, new NetFault(required(request.type(), "type"),
                request.factor() == null ? 1.0 : request.factor(), request.place()));
        return control.snapshot(id);
    }

    @DeleteMapping("/simulations/{id}/faults/transitions/{transition}")
    Snapshot clearNetFault(@PathVariable("id") UUID id, @PathVariable("transition") String transition) {
        control.setNetFault(id, transition, null);
        return control.snapshot(id);
    }

    @PutMapping("/simulations/{id}/modules")
    Snapshot setModules(@PathVariable("id") UUID id, @RequestBody ModulesRequest request) {
        control.setActiveModules(id, request.modules() == null ? Set.of() : request.modules());
        return control.snapshot(id);
    }

    // ---- trying out the net and observing it ----

    @PostMapping("/simulations/{id}/commands")
    ResponseEntity<CommandResponse> command(@PathVariable("id") UUID id, @RequestBody CommandRequest request)
            throws InterruptedException {
        CommandHandle handle = control.command(id, required(request.place(), "place"),
                request.parameters() == null ? Map.of() : request.parameters(),
                request.returnsAfter() == null ? List.of() : request.returnsAfter(), "control");
        if (!Boolean.TRUE.equals(request.await())) {
            return ResponseEntity.accepted().body(new CommandResponse(handle.commandId(), handle.completion().getNow(null)));
        }
        Duration timeout = request.timeout() == null ? Duration.ofMinutes(1) : request.timeout();
        try {
            return ResponseEntity.ok(new CommandResponse(handle.commandId(),
                    handle.completion().get(timeout.toMillis(), TimeUnit.MILLISECONDS)));
        } catch (TimeoutException | ExecutionException exception) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(new CommandResponse(handle.commandId(), null));
        }
    }

    @GetMapping("/simulations/{id}/sensors")
    Map<String, Object> sensors(@PathVariable("id") UUID id) {
        return new LinkedHashMap<>(control.readSensors(id));
    }

    @GetMapping("/simulations/{id}/events")
    List<SimulationEvent> events(@PathVariable("id") UUID id, @RequestParam(name = "from", defaultValue = "0") int from) {
        return control.events(id, from);
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }
}
