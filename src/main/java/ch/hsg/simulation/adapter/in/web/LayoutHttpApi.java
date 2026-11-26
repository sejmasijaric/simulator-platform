package ch.hsg.simulation.adapter.in.web;

import ch.hsg.simulation.adapter.TemplateValues;
import ch.hsg.simulation.core.NotFoundException;
import ch.hsg.simulation.core.SimulationException;
import ch.hsg.simulation.core.event.SimulationEvent;
import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.net.NetDefinition.ErrorResponse;
import ch.hsg.simulation.core.net.NetDefinition.Parameter;
import ch.hsg.simulation.core.net.NetDefinition.Route;
import ch.hsg.simulation.core.net.NetDefinition.RouteKind;
import ch.hsg.simulation.core.net.Templates;
import ch.hsg.simulation.core.port.CommandHandle;
import ch.hsg.simulation.core.port.SimulationControl;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The generic layout adapter (ADR 0009, revised): answers the routes every layout declares in
 * its YAML, the same way its physical interface does. It checks the parameters in the declared
 * order (format validation), then either puts a request token into the route's command place
 * and waits until one of its ending transitions has fired for it, or reads a sensor. It talks to
 * the most recently started running simulation of the route's layout and switches it to real
 * time if needed, since an unchanged application expects the physical durations.
 *
 * <p>Plain Java so it can be tested without a web server; {@link LayoutHttpFilter} connects it
 * to HTTP.
 */
public final class LayoutHttpApi {

    /** An incoming call: method, path, query parameters (first value each) and URL without query. */
    public record Request(String method, String path, Map<String, String> parameters, String url) {
    }

    public record Response(int status, String body) {
    }

    private record Endpoint(NetDefinition layout, Route route) {
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final String ORIGIN = "application";

    private final SimulationControl control;
    private final Clock clock;
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();

    public LayoutHttpApi(SimulationControl control, Clock clock) {
        this.control = control;
        this.clock = clock;
        for (NetDefinition layout : control.layouts()) {
            for (Route route : layout.routes()) {
                Endpoint previous = endpoints.put(key(route.method(), route.path()), new Endpoint(layout, route));
                if (previous != null) {
                    throw new IllegalArgumentException("Layouts " + previous.layout().id() + " and " + layout.id()
                            + " both declare " + route.method() + " " + route.path());
                }
            }
        }
    }

    /** Whether some layout declares this endpoint. */
    public boolean handles(String method, String path) {
        return endpoints.containsKey(key(method, path));
    }

    /** The answer, or empty if no layout declares this endpoint. */
    public Optional<Response> handle(Request request) {
        Endpoint endpoint = endpoints.get(key(request.method(), request.path()));
        if (endpoint == null) {
            return Optional.empty();
        }
        LocalDateTime started = LocalDateTime.now(clock);
        Route route = endpoint.route();

        Map<String, Object> parameters = new LinkedHashMap<>(request.parameters());
        for (Parameter parameter : route.parameters()) {
            String value = request.parameters().get(parameter.name());
            if (value == null || value.isEmpty()) {
                if (parameter.required()) {
                    return Optional.of(error(parameter.missing(), value, 400, "Missing parameter " + parameter.name()));
                }
                if (parameter.defaultValue() == null) {
                    continue;
                }
                value = parameter.defaultValue();
            }
            if (parameter.integer() && !isInteger(value)) {
                return Optional.of(error(parameter.invalid(), value, 400, "Parameter " + parameter.name() + " must be a whole number"));
            }
            if (!parameter.values().isEmpty() && !parameter.values().contains(value)) {
                return Optional.of(error(parameter.invalid(), value, 400, "Invalid value for " + parameter.name()));
            }
            parameters.put(parameter.name(), value);
        }

        Optional<UUID> running = control.running(endpoint.layout().id());
        if (running.isEmpty()) {
            return Optional.of(json(503, "No running " + endpoint.layout().name()
                    + " simulation. Create and start one in the web UI."));
        }
        UUID simulation = running.get();
        try {
            if (control.snapshot(simulation).speed() == null) {
                control.setSpeed(simulation, 1.0);
            }
            return Optional.of(switch (route.kind()) {
                case COMMAND -> command(endpoint, simulation, parameters, request, started);
                case QUERY -> query(endpoint, simulation, parameters, request, started);
                case SETTING -> setting(endpoint, simulation, parameters, request, started);
            });
        } catch (NotFoundException | SimulationException exception) {
            return Optional.of(json(503, exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return Optional.of(json(500, "Layout " + endpoint.layout().id() + ", route " + route.name() + ": "
                    + exception.getMessage()));
        }
    }

    private Response command(Endpoint endpoint, UUID simulation, Map<String, Object> parameters, Request request,
                             LocalDateTime started) {
        Route route = endpoint.route();
        CommandHandle handle = control.command(simulation, route.command(), parameters, route.returnsAfter(), ORIGIN);
        Duration timeout = route.timeout() == null ? DEFAULT_TIMEOUT : route.timeout();
        SimulationEvent completed;
        try {
            completed = handle.completion().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return json(503, "Interrupted while waiting for " + route.name());
        } catch (TimeoutException exception) {
            return json(504, route.name() + " did not finish within " + timeout);
        } catch (ExecutionException | CancellationException exception) {
            return json(500, "The simulation was reset while " + route.name() + " was running");
        }
        Object result = completed.data().get("result");
        TemplateValues values = new TemplateValues()
                .parameters(parameters)
                .sensors(() -> control.readSensors(simulation))
                .result(result instanceof Map<?, ?> map ? stringKeys(map) : Map.of())
                .with("request.url", request.url())
                .times(started, LocalDateTime.now(clock));
        return success(endpoint, route, values);
    }

    private Response query(Endpoint endpoint, UUID simulation, Map<String, Object> parameters, Request request,
                           LocalDateTime started) {
        Route route = endpoint.route();
        Map<String, Object> sensors = control.readSensors(simulation);
        TemplateValues values = new TemplateValues()
                .parameters(parameters)
                .sensors(() -> sensors)
                .with("request.url", request.url())
                .times(started, LocalDateTime.now(clock));
        if (route.reads() != null) {
            String name = Templates.render(route.reads(), values);
            if (sensors.containsKey(name)) {
                values.with("read", sensors.get(name));
            } else if (endpoint.layout().setting(name).isPresent()) {
                values.with("read", control.snapshot(simulation).settings().get(name));
            } else {
                return json(400, "Route " + route.name() + " reads unknown sensor or setting " + name);
            }
        }
        return success(endpoint, route, values);
    }

    /** Changes settings (e.g. a motor speed); nothing fires because of it, but waiting steps may now start. */
    private Response setting(Endpoint endpoint, UUID simulation, Map<String, Object> parameters, Request request,
                             LocalDateTime started) {
        Route route = endpoint.route();
        TemplateValues values = new TemplateValues().parameters(parameters);
        for (Map.Entry<String, String> set : route.sets().entrySet()) {
            String name = Templates.render(set.getKey(), values);
            String value = Templates.render(set.getValue(), values);
            if (endpoint.layout().setting(name).isEmpty()) {
                return json(400, "Route " + route.name() + " sets unknown setting " + name);
            }
            int number;
            try {
                number = Integer.parseInt(value.trim());
            } catch (NumberFormatException exception) {
                return json(500, "invalid literal for int() with base 10: '" + value + "'");
            }
            control.setSetting(simulation, name, number, ORIGIN);
        }
        if (!route.resets().isEmpty()) {
            control.resetSettings(simulation, route.resets(), ORIGIN);
        }
        values.sensors(() -> control.readSensors(simulation))
                .with("request.url", request.url())
                .times(started, LocalDateTime.now(clock));
        return success(endpoint, route, values);
    }

    private static Response success(Endpoint endpoint, Route route, TemplateValues values) {
        String body = route.body() == null ? "" : Templates.render(route.body(), values);
        String envelope = endpoint.layout().http().envelope();
        if (envelope == null) {
            return new Response(200, body);
        }
        values.with("body", body.isEmpty() ? "null" : body);
        return new Response(200, Templates.render(envelope, values));
    }

    private static Response error(ErrorResponse declared, String value, int fallbackStatus, String fallbackMessage) {
        if (declared == null) {
            return json(fallbackStatus, fallbackMessage + (value == null ? "" : ": " + value));
        }
        TemplateValues values = new TemplateValues().with("value", value == null ? "" : value);
        return new Response(declared.status(), Templates.render(declared.body(), values));
    }

    private static Response json(int status, String message) {
        return new Response(status, "{\"error\": \"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String key(String method, String path) {
        return method.toUpperCase() + " " + path;
    }
}
