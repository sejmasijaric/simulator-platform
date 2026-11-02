package ch.hsg.simulation.adapters.fischertechnik;

import ch.hsg.simulation.app.RealTimeService;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.model.ComponentState;
import ch.hsg.simulation.model.ComponentView;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.model.SimulationStatus;
import ch.hsg.simulation.runtime.SimulationManager;
import ch.hsg.simulation.systems.fischertechnik.FischertechnikLayoutDefinition;
import ch.hsg.simulation.systems.fischertechnik.MillingMachine;
import ch.hsg.simulation.systems.fischertechnik.Oven;
import ch.hsg.simulation.systems.fischertechnik.SortingMachine;
import ch.hsg.simulation.systems.fischertechnik.Transport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Hardware-compatible HTTP API of the Fischertechnik factory (ADR 0009). Paths, parameters,
 * responses and errors follow the physical {@code app.py}, see
 * docs/layouts/fischertechnik/http-api.md. Commands block until done; commands for one machine
 * wait for each other like the factory's queue. Bound to the one running simulation of the layout,
 * which is switched to real time if needed.
 */
@RestController
public class FischertechnikController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd-MMM-yyyy (HH:mm:ss.SSSSSS)", Locale.ENGLISH);
    private static final String CONTROLLER_ERROR =
            "400 Bad Request: The machine controller of the addressed machine encountered an error: <class 'ValueError'>:";
    private static final String WRONG_MACHINE = "404 Not Found: Please make sure that the value of the parameter for the machine is 1";
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final SimulationManager manager;
    private final RealTimeService realTime;
    private final Map<String, ReentrantLock> queues = new ConcurrentHashMap<>();

    public FischertechnikController(SimulationManager manager, RealTimeService realTime) {
        this.manager = manager;
        this.realTime = realTime;
    }

    @GetMapping("/vgr/pick_up_and_transport")
    ResponseEntity<Map<String, Object>> vgrPickUpAndTransport(HttpServletRequest request, @RequestParam Map<String, String> params) {
        return command(request, params, "vgr", List.of("start", "end"), Transport.PICK_UP_AND_TRANSPORT,
                Map.of("start", params.getOrDefault("start", ""), "end", params.getOrDefault("end", "")), state -> List.of());
    }

    @GetMapping("/wt/pick_up_and_transport")
    ResponseEntity<Map<String, Object>> wtPickUpAndTransport(HttpServletRequest request, @RequestParam Map<String, String> params) {
        return command(request, params, "wt", List.of("start", "end"), Transport.PICK_UP_AND_TRANSPORT,
                Map.of("start", params.getOrDefault("start", ""), "end", params.getOrDefault("end", "")), state -> List.of());
    }

    @GetMapping("/ov/burn")
    ResponseEntity<Map<String, Object>> ovBurn(HttpServletRequest request, @RequestParam Map<String, String> params) {
        Optional<Integer> time = integer(params.getOrDefault("time", "2"));
        if (time.isEmpty()) {
            return invalidInteger(params.get("time"));
        }
        return command(request, params, "ov", List.of(), Oven.BURN, Map.of("time", time.get()), state -> List.of());
    }

    @GetMapping("/mm/mill")
    ResponseEntity<Map<String, Object>> mmMill(HttpServletRequest request, @RequestParam Map<String, String> params) {
        Optional<Integer> time = integer(params.getOrDefault("time", "2"));
        if (time.isEmpty()) {
            return invalidInteger(params.get("time"));
        }
        return command(request, params, "mm", List.of("start", "end"), MillingMachine.MILL,
                Map.of("start", params.getOrDefault("start", ""), "end", params.getOrDefault("end", ""), "time", time.get()),
                state -> List.of());
    }

    @GetMapping("/sm/detect_color")
    ResponseEntity<Map<String, Object>> smDetectColor(HttpServletRequest request, @RequestParam Map<String, String> params) {
        return command(request, params, "sm", List.of(), SortingMachine.DETECT_COLOR, Map.of(),
                state -> List.of(Map.of("color", state.attributes().get(SortingMachine.LAST_COLOR))));
    }

    /** Like the physical API, any {@code use_nfc} value (even {@code false}) turns NFC on. */
    @GetMapping("/sm/sort")
    ResponseEntity<Map<String, Object>> smSort(HttpServletRequest request, @RequestParam Map<String, String> params) {
        return command(request, params, "sm", List.of(), SortingMachine.SORT,
                Map.of("start", params.getOrDefault("start", "None"),
                        "predefined_ejection_location", params.getOrDefault("predefined_ejection_location", "None"),
                        "use_nfc", params.get("use_nfc") != null && !params.get("use_nfc").isEmpty()),
                state -> {
                    Map<String, Object> sink = new LinkedHashMap<>();
                    Object value = state.attributes().get(SortingMachine.LAST_SINK);
                    sink.put("sink", "".equals(value) ? null : value);
                    return List.of(sink);
                });
    }

    @GetMapping({"/vgr/status_of_light_barrier", "/ov/status_of_light_barrier", "/mm/status_of_light_barrier", "/sm/status_of_light_barrier"})
    ResponseEntity<Map<String, Object>> statusOfLightBarrier(HttpServletRequest request, @RequestParam Map<String, String> params) {
        String type = typeOf(request);
        return query(request, params, type, List.of("lb"), snapshot -> {
            Optional<Integer> lb = integer(params.get("lb"));
            if (lb.isEmpty()) {
                return invalidInteger(params.get("lb"));
            }
            Optional<ComponentView> barrier = snapshot.component(type + "_1_lb" + lb.get());
            if (barrier.isEmpty()) {
                return error(HttpStatus.BAD_REQUEST, CONTROLLER_ERROR + "Light Barrier " + lb.get() + " does not exist");
            }
            return ok(request, List.of(Map.of("interrupted", barrier.get().state().attributes().get("interrupted"))));
        });
    }

    @GetMapping({"/vgr/state_of_machine", "/ov/state_of_machine", "/wt/state_of_machine", "/mm/state_of_machine", "/sm/state_of_machine"})
    ResponseEntity<Map<String, Object>> stateOfMachine(HttpServletRequest request, @RequestParam Map<String, String> params) {
        String type = typeOf(request);
        return query(request, params, type, List.of(), snapshot -> ok(request,
                List.of(Map.of("state", snapshot.component(type + "_1").orElseThrow().state().attributes().get("current_state")))));
    }

    private ResponseEntity<Map<String, Object>> command(HttpServletRequest request, Map<String, String> params, String type,
                                                        List<String> required, String capability, Map<String, Object> parameters,
                                                        Function<ComponentState, List<Map<String, Object>>> attributes) {
        LocalDateTime started = LocalDateTime.now();
        return withSimulation(params, required, simulationId -> {
            String machine = type + "_1";
            ReentrantLock queue = queues.computeIfAbsent(simulationId + "/" + machine, key -> new ReentrantLock(true));
            try {
                queue.lockInterruptibly();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return error(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while waiting for " + machine);
            }
            try {
                ComponentAction action = new ComponentAction(capability, parameters, null, ComponentAction.ORIGIN_APPLICATION);
                ComponentExecutionResult result = manager.execute(simulationId, machine, action);
                if (!result.isExecuted()) {
                    return error(HttpStatus.BAD_REQUEST, CONTROLLER_ERROR + result.errorMessage().orElse("unknown error"));
                }
                manager.engine(simulationId).completionOf(action.commandId()).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                ComponentState state = manager.getSimulation(simulationId).component(machine).orElseThrow().state();
                return ok(request, started, attributes.apply(state));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return error(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while " + machine + " was running");
            } catch (ExecutionException | CancellationException exception) {
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "The simulation was reset while " + machine + " was running");
            } catch (TimeoutException exception) {
                return error(HttpStatus.GATEWAY_TIMEOUT, machine + " did not finish within " + TIMEOUT.toMinutes() + " minutes");
            } finally {
                queue.unlock();
            }
        });
    }

    private ResponseEntity<Map<String, Object>> query(HttpServletRequest request, Map<String, String> params, String type,
                                                      List<String> required,
                                                      Function<SimulationSnapshot, ResponseEntity<Map<String, Object>>> answer) {
        return withSimulation(params, required, simulationId -> answer.apply(manager.getSimulation(simulationId)));
    }

    /** Checks the parameters like {@code app.py} does, then finds the running simulation. */
    private ResponseEntity<Map<String, Object>> withSimulation(Map<String, String> params, List<String> required,
                                                              Function<UUID, ResponseEntity<Map<String, Object>>> body) {
        String machine = params.get("machine");
        if (machine == null || machine.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "404 Not Found: No Machine is passed");
        }
        String[] parts = machine.split("_");
        if (parts.length < 2) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "list index out of range");
        }
        if (!"1".equals(parts[1]) || required.stream().anyMatch(name -> params.get(name) == null || params.get(name).isEmpty())) {
            return error(HttpStatus.NOT_FOUND, WRONG_MACHINE);
        }
        List<UUID> running = manager.getSimulations().stream()
                .filter(snapshot -> snapshot.definitionId().equals(FischertechnikLayoutDefinition.ID))
                .filter(snapshot -> snapshot.status() == SimulationStatus.RUNNING)
                .map(SimulationSnapshot::id)
                .toList();
        if (running.isEmpty()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "No running Fischertechnik simulation. Create and start one in the web UI.");
        }
        if (running.size() > 1) {
            return error(HttpStatus.CONFLICT, "Several Fischertechnik simulations are running. Stop all but one.");
        }
        UUID simulationId = running.get(0);
        if (realTime.speedFactor(simulationId).isEmpty()) {
            realTime.enable(simulationId, 1.0);
        }
        return body.apply(simulationId);
    }

    private static ResponseEntity<Map<String, Object>> ok(HttpServletRequest request, List<Map<String, Object>> attributes) {
        return ok(request, LocalDateTime.now(), attributes);
    }

    private static ResponseEntity<Map<String, Object>> ok(HttpServletRequest request, LocalDateTime started,
                                                          List<Map<String, Object>> attributes) {
        LocalDateTime ended = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attributes", attributes);
        body.put("end_time", TIMESTAMP.format(ended));
        body.put("link", request.getRequestURL().toString());
        body.put("process_time", pythonTimedelta(Duration.between(started, ended)));
        body.put("start_time", TIMESTAMP.format(started));
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private static ResponseEntity<Map<String, Object>> invalidInteger(String value) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "invalid literal for int() with base 10: '" + value + "'");
    }

    private static Optional<Integer> integer(String value) {
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String typeOf(HttpServletRequest request) {
        return request.getRequestURI().split("/")[1];
    }

    /** Python's {@code str(timedelta)}: {@code H:MM:SS.ffffff}, without fraction when it is zero. */
    static String pythonTimedelta(Duration duration) {
        long micros = duration.toNanos() / 1_000 % 1_000_000;
        String base = "%d:%02d:%02d".formatted(duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
        return micros == 0 ? base : base + ".%06d".formatted(micros);
    }
}
