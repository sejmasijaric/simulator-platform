package ch.hsg.simulation.adapters.dobot;

import ch.hsg.simulation.app.RealTimeService;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.model.SimulationStatus;
import ch.hsg.simulation.runtime.SimulationManager;
import ch.hsg.simulation.systems.dobot.DobotArm;
import ch.hsg.simulation.systems.dobot.DobotLayoutDefinition;
import ch.hsg.simulation.systems.dobot.Playback;
import ch.hsg.simulation.systems.dobot.Pose;
import ch.hsg.simulation.systems.dobot.Target;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hardware-compatible HTTP API of the lab's Dobot (ADR 0009). Paths, parameters, responses and
 * errors follow the lab's Flask {@code app.py}, see docs/layouts/dobot/http-api.md: success is
 * JSON with sorted keys, errors are Werkzeug's HTML error pages. Commands block until the arm is
 * done and run one at a time. Bound to the one running simulation of the layout, which is switched
 * to real time if needed.
 */
@RestController
public class DobotController {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final String UNPACK_ERROR = "too many values to unpack (expected 4)";
    private static final String GENERIC_BAD_REQUEST = "The browser (or proxy) sent a request that this server could not understand.";
    private static final String GENERIC_SERVER_ERROR = "The server encountered an internal error and was unable to complete your "
            + "request. Either the server is overloaded or there is an error in the application.";

    private final SimulationManager manager;
    private final RealTimeService realTime;
    private final Map<UUID, ReentrantLock> arms = new ConcurrentHashMap<>();

    public DobotController(SimulationManager manager, RealTimeService realTime) {
        this.manager = manager;
        this.realTime = realTime;
    }

    /** Moves to {@code start}, then to {@code end}. All errors are wrapped in {@code Movement error: …}. */
    @GetMapping("/move_to")
    ResponseEntity<Object> moveTo(@RequestParam(required = false) String start, @RequestParam(required = false) String end) {
        if (isEmpty(start) || isEmpty(end)) {
            return html(HttpStatus.BAD_REQUEST, "Both start and end parameters are required");
        }
        Target startTarget;
        Target endTarget;
        try {
            startTarget = Target.parse(start, DobotArm.STONE_POSITIONS);
            endTarget = Target.parse(end, DobotArm.STONE_POSITIONS);
        } catch (IllegalArgumentException exception) {
            return html(HttpStatus.BAD_REQUEST, "Movement error: 400 Bad Request: " + exception.getMessage());
        }
        if (startTarget.isFactory()) {
            return html(HttpStatus.BAD_REQUEST, "Movement error: " + UNPACK_ERROR);
        }
        ResponseEntity<Object> response = run(DobotArm.MOVE_TO, Map.of("start", start, "end", end));
        if (response.getStatusCode().is2xxSuccessful() && endTarget.isFactory()) {
            // The lab code reaches start, then fails to unpack "factory" as coordinates.
            return html(HttpStatus.BAD_REQUEST, "Movement error: " + UNPACK_ERROR);
        }
        return response.getStatusCode().is2xxSuccessful() ? moved(startTarget, endTarget) : response;
    }

    @GetMapping("/pick_up_and_transport")
    ResponseEntity<Object> pickUpAndTransport(@RequestParam(required = false) String start, @RequestParam(required = false) String end) {
        if (isEmpty(start) || isEmpty(end)) {
            return html(HttpStatus.BAD_REQUEST, "Both start and end parameters are required");
        }
        Target startTarget;
        Target endTarget;
        try {
            startTarget = Target.parse(start, DobotArm.STONE_POSITIONS);
            endTarget = Target.parse(end, DobotArm.STONE_POSITIONS);
        } catch (IllegalArgumentException exception) {
            return html(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        ResponseEntity<Object> response = run(DobotArm.PICK_UP_AND_TRANSPORT, Map.of("start", start, "end", end));
        return response.getStatusCode().is2xxSuccessful() ? moved(startTarget, endTarget) : response;
    }

    /**
     * Runs a DobotStudio playback. Like the lab code, the file must be sent as part {@code file}
     * and a part {@code file_path} must be present too, otherwise nothing moves.
     */
    @PostMapping("/run_xml")
    ResponseEntity<Object> runXml(HttpServletRequest request) {
        Map<String, MultipartFile> files = request instanceof MultipartHttpServletRequest multipart ? multipart.getFileMap() : Map.of();
        MultipartFile file = files.get("file");
        if (file == null) {
            return html(HttpStatus.BAD_REQUEST, GENERIC_BAD_REQUEST);
        }
        if (!files.containsKey("file_path")) {
            return ResponseEntity.ok(Map.of("message", "No file part."));
        }
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "No selected file."));
        }
        String xml;
        try {
            xml = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.getBytes())).toString();
        } catch (CharacterCodingException exception) {
            return html(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR);
        } catch (IOException exception) {
            return html(HttpStatus.BAD_REQUEST, GENERIC_BAD_REQUEST);
        }
        ResponseEntity<Object> response = run(DobotArm.RUN_PLAYBACK, Map.of("playback", xml));
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        try {
            Playback.parse(xml);
        } catch (IllegalArgumentException exception) {
            // The lab code calibrates and moves home before it parses the file.
            return html(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR);
        }
        return ResponseEntity.ok(Map.of("message", "Dobot has finished executing commands."));
    }

    private ResponseEntity<Object> run(String capability, Map<String, Object> parameters) {
        List<UUID> running = manager.getSimulations().stream()
                .filter(snapshot -> snapshot.definitionId().equals(DobotLayoutDefinition.ID))
                .filter(snapshot -> snapshot.status() == SimulationStatus.RUNNING)
                .map(SimulationSnapshot::id)
                .toList();
        if (running.isEmpty()) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, "No running Dobot simulation. Create and start one in the web UI.");
        }
        if (running.size() > 1) {
            return json(HttpStatus.CONFLICT, "Several Dobot simulations are running. Stop all but one.");
        }
        UUID simulationId = running.get(0);
        if (realTime.speedFactor(simulationId).isEmpty()) {
            realTime.enable(simulationId, 1.0);
        }
        ReentrantLock arm = arms.computeIfAbsent(simulationId, key -> new ReentrantLock(true));
        try {
            arm.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return json(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while waiting for the Dobot");
        }
        try {
            ComponentAction action = new ComponentAction(capability, parameters, null, ComponentAction.ORIGIN_APPLICATION);
            ComponentExecutionResult result = manager.execute(simulationId, DobotLayoutDefinition.ARM, action);
            if (!result.isExecuted()) {
                return html(HttpStatus.BAD_REQUEST, result.errorMessage().orElse("unknown error"));
            }
            manager.engine(simulationId).completionOf(action.commandId()).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return ResponseEntity.ok().build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return json(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while the Dobot was moving");
        } catch (ExecutionException | CancellationException exception) {
            return json(HttpStatus.INTERNAL_SERVER_ERROR, "The simulation was reset while the Dobot was moving");
        } catch (TimeoutException exception) {
            return json(HttpStatus.GATEWAY_TIMEOUT, "The Dobot did not finish within " + TIMEOUT.toMinutes() + " minutes");
        } finally {
            arm.unlock();
        }
    }

    /** Flask's {@code jsonify} sorts keys; positions are lists, or the string {@code factory}. */
    private static ResponseEntity<Object> moved(Target start, Target end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("end_position", position(end));
        body.put("message", "Moved from " + python(start) + " to " + python(end));
        body.put("start_position", position(start));
        body.put("status", "success");
        return ResponseEntity.ok(body);
    }

    private static Object position(Target target) {
        if (target.isFactory()) {
            return Target.FACTORY;
        }
        Pose pose = target.pose();
        return List.of(pose.x(), pose.y(), pose.z(), pose.r());
    }

    /** Python's {@code str()} of the parsed position: a 4-tuple of floats, or {@code factory}. */
    private static String python(Target target) {
        if (target.isFactory()) {
            return Target.FACTORY;
        }
        Pose pose = target.pose();
        return "(" + pythonFloat(pose.x()) + ", " + pythonFloat(pose.y()) + ", " + pythonFloat(pose.z()) + ", "
                + pythonFloat(pose.r()) + ")";
    }

    /** Python's {@code repr(float)}: shortest digits, scientific below 1e-4 and from 1e16. */
    static String pythonFloat(double value) {
        if (value == 0) {
            return 1 / value < 0 ? "-0.0" : "0.0";
        }
        BigDecimal decimal = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        double magnitude = Math.abs(value);
        if (magnitude >= 1e-4 && magnitude < 1e16) {
            String plain = decimal.toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        String digits = decimal.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return (value < 0 ? "-" : "") + mantissa + "e" + (exponent < 0 ? "-" : "+") + "%02d".formatted(Math.abs(exponent));
    }

    /** Werkzeug's HTML error page. */
    private static ResponseEntity<Object> html(HttpStatus status, String description) {
        String body = "<!doctype html>\n<html lang=en>\n<title>" + status.value() + " " + status.getReasonPhrase() + "</title>\n"
                + "<h1>" + status.getReasonPhrase() + "</h1>\n<p>" + escape(description) + "</p>\n";
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(body);
    }

    private static ResponseEntity<Object> json(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    /** MarkupSafe's {@code escape}. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&#34;").replace("'", "&#39;");
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
