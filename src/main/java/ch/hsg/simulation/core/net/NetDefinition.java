package ch.hsg.simulation.core.net;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Everything that differs between layouts: a timed, coloured Petri net describing what is
 * physically possible, the sensors applications can read, the routes and messages of the
 * layout's own interface, a picture of it and the workpieces a user can place. Immutable;
 * loaded once (e.g. from YAML) and shared by all simulations of the layout. The constructor
 * validates that all names refer to each other.
 */
public record NetDefinition(
        String id,
        String name,
        String description,
        List<Place> places,
        List<Transition> transitions,
        List<Sensor> sensors,
        HttpInterface http,
        List<EventTopic> events,
        Image image,
        List<TokenTemplate> tokenTemplates) {

    /** No capacity limit. */
    public static final int UNLIMITED = 0;

    // ---- the net ----

    /**
     * A position, a machine state (a resource place holding one token while the machine is
     * ready) or a controller's queue of requests (a command place).
     */
    public record Place(String name, int capacity, int initial, String module) {
        public Place {
            requireName(name, "place");
            if (capacity < 0 || initial < 0) {
                throw new IllegalArgumentException("Place " + name + ": capacity and initial must not be negative");
            }
            if (capacity != UNLIMITED && initial > capacity) {
                throw new IllegalArgumentException("Place " + name + ": initial tokens exceed its capacity");
            }
        }

        public static Place of(String name) {
            return new Place(name, UNLIMITED, 0, null);
        }
    }

    /**
     * One token flowing in or out of a transition. {@code place} may be a template such as
     * {@code ${start}}, filled from the request parameters carried by the tokens the same
     * transition consumes before it. {@code variable} names the token so an output arc can pass
     * the same token on (a workpiece, a job); without a variable an input takes any token and an
     * output creates a new one (a resource or signal).
     */
    public record Arc(String place, String variable) {
        public Arc {
            requireName(place, "arc place");
        }

        public static Arc of(String place) {
            return new Arc(place, null);
        }

        public static Arc of(String place, String variable) {
            return new Arc(place, variable);
        }

        public boolean isTemplate() {
            return Templates.isTemplate(place);
        }
    }

    /**
     * A physical action. Its input tokens are taken when it starts and its outputs appear
     * {@code duration} later; with a {@code durationParameter} the duration grows by
     * {@code durationPerUnit} for each unit of that request parameter (e.g. {@code time}).
     *
     * <p>{@code guard}: equality conditions that must hold, keyed by {@code variable.attribute}
     * (e.g. {@code wp.color}) or a request parameter, the value possibly a template
     * ({@code ${color}}); a value starting with {@code !} must not be equal. {@code inhibitors}:
     * places that must be empty (inhibitor arcs), possibly templates ({@code ${start}}). {@code result}:
     * templates rendered when it fires and handed back to the request that waits for it.
     * Higher {@code priority} wins when transitions compete for a token; ties go to declaration
     * order.
     */
    public record Transition(String name, List<Arc> inputs, List<Arc> outputs, Duration duration,
                             String durationParameter, Duration durationPerUnit, Map<String, String> guard,
                             List<String> inhibitors, Map<String, String> result, int priority, String module) {
        public Transition {
            requireName(name, "transition");
            inputs = List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            duration = duration == null ? Duration.ZERO : duration;
            guard = guard == null ? Map.of() : unmodifiable(guard);
            inhibitors = inhibitors == null ? List.of() : List.copyOf(inhibitors);
            result = result == null ? Map.of() : unmodifiable(result);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Transition " + name + " needs at least one input");
            }
            if (duration.isNegative() || (durationPerUnit != null && durationPerUnit.isNegative())) {
                throw new IllegalArgumentException("Transition " + name + ": duration must not be negative");
            }
            if ((durationParameter == null) != (durationPerUnit == null)) {
                throw new IllegalArgumentException("Transition " + name
                        + ": a duration per unit needs both a parameter and a unit");
            }
            Set<String> bound = new HashSet<>();
            for (Arc arc : inputs) {
                if (arc.variable() != null && !bound.add(arc.variable())) {
                    throw new IllegalArgumentException("Transition " + name + " binds " + arc.variable() + " twice");
                }
            }
            for (Arc arc : outputs) {
                if (arc.variable() != null && !bound.contains(arc.variable())) {
                    throw new IllegalArgumentException("Transition " + name + ": output variable "
                            + arc.variable() + " is not bound by an input");
                }
            }
            for (String key : guard.keySet()) {
                int dot = key.indexOf('.');
                if (dot > 0 && !bound.contains(key.substring(0, dot))) {
                    throw new IllegalArgumentException("Transition " + name + ": guard " + key
                            + " refers to an unbound variable");
                }
            }
        }

        /** A transition without guard, inhibitors, result or parameter-dependent duration. */
        public static Transition of(String name, List<Arc> inputs, List<Arc> outputs, Duration duration) {
            return new Transition(name, inputs, outputs, duration, null, null, null, null, null, 0, null);
        }
    }

    // ---- what applications read ----

    /** How a sensor derives its value from the marking. */
    public enum SensorKind {
        /** true while at least one token is in {@code place}. */
        OCCUPIED,
        /** number of tokens in {@code place}. */
        COUNT,
        /** {@code attribute} of the first token in {@code place}, or null if empty. */
        ATTRIBUTE
    }

    /**
     * A value an application can read. {@code values} optionally translates the derived value
     * (as text: {@code true}, {@code 3}, {@code red}, {@code null}) to what the hardware reports,
     * e.g. {@code {true: 0, false: 1}} for a raw light barrier.
     */
    public record Sensor(String name, SensorKind kind, String place, String attribute, Map<String, Object> values) {
        public Sensor {
            requireName(name, "sensor");
            Objects.requireNonNull(kind, "kind");
            requireName(place, "sensor place");
            if (kind == SensorKind.ATTRIBUTE && (attribute == null || attribute.isBlank())) {
                throw new IllegalArgumentException("Sensor " + name + " needs an attribute");
            }
            values = values == null ? Map.of() : unmodifiable(values);
        }

        public static Sensor occupied(String name, String place) {
            return new Sensor(name, SensorKind.OCCUPIED, place, null, null);
        }
    }

    // ---- the layout's own interface ----

    /**
     * The layout's HTTP API. {@code envelope}, if set, wraps every successful route body
     * (placeholder {@code ${body}}), e.g. the factory's common response with timestamps.
     */
    public record HttpInterface(String envelope, List<Route> routes) {
        public HttpInterface {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }

        public static HttpInterface none() {
            return new HttpInterface(null, List.of());
        }
    }

    public enum RouteKind {
        /** Puts a request token into {@code command} and answers after {@code returnsAfter} fired for it. */
        COMMAND,
        /** Reads sensors and fires nothing. */
        QUERY
    }

    /**
     * One endpoint. {@code parameters} are checked in order before anything happens (format
     * validation, like the physical API). A command answers once any of {@code returnsAfter} has
     * fired for it: a request can end in several ways, e.g. with or without a workpiece. {@code reads} names the sensor a query answers about,
     * possibly a template ({@code sm_lb${lb}}). {@code body} is the response body template.
     */
    public record Route(String name, RouteKind kind, String method, String path, List<Parameter> parameters,
                        String command, List<String> returnsAfter, Duration timeout, String reads, String body) {
        public Route {
            requireName(name, "route");
            Objects.requireNonNull(kind, "kind");
            requireName(method, "route method");
            requireName(path, "route path");
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            returnsAfter = returnsAfter == null ? List.of() : List.copyOf(returnsAfter);
            if (kind == RouteKind.COMMAND) {
                requireName(command, "route " + name + " command place");
            }
        }
    }

    /**
     * A request parameter: whether it must be there, which values are valid, its default, and
     * the answers the physical API gives when it is missing or invalid.
     */
    public record Parameter(String name, boolean required, List<String> values, String defaultValue,
                            boolean integer, ErrorResponse missing, ErrorResponse invalid) {
        public Parameter {
            requireName(name, "parameter");
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /** An error answer: HTTP status and body template ({@code ${value}} is the offending value). */
    public record ErrorResponse(int status, String body) {
    }

    /** A message the layout publishes, e.g. an MQTT topic with a station's full state every 500 ms. */
    public record EventTopic(String protocol, String topic, Duration every, String payload) {
        public EventTopic {
            requireName(protocol, "event protocol");
            requireName(topic, "event topic");
        }
    }

    // ---- for the UI ----

    /** A picture of the layout and where each place is on it, in pixels. */
    public record Image(String url, int width, int height, Map<String, Point> positions) {
        public Image {
            positions = positions == null ? Map.of() : unmodifiable(positions);
        }
    }

    public record Point(int x, int y) {
    }

    /** A kind of token a user can place by hand, e.g. a red workpiece. */
    public record TokenTemplate(String name, Map<String, Object> attributes) {
        public TokenTemplate {
            requireName(name, "token template");
            attributes = attributes == null ? Map.of() : unmodifiable(attributes);
        }
    }

    // ---- the definition ----

    public NetDefinition {
        requireName(id, "layout id");
        name = name == null ? id : name;
        description = description == null ? "" : description;
        places = List.copyOf(places);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        sensors = sensors == null ? List.of() : List.copyOf(sensors);
        http = http == null ? HttpInterface.none() : http;
        events = events == null ? List.of() : List.copyOf(events);
        tokenTemplates = tokenTemplates == null ? List.of() : List.copyOf(tokenTemplates);
        validate(id, places, transitions, sensors, http, image);
    }

    /** A net without interface, picture or templates, e.g. for tests. */
    public static NetDefinition of(String id, List<Place> places, List<Transition> transitions, List<Sensor> sensors) {
        return new NetDefinition(id, null, null, places, transitions, sensors, null, null, null, null);
    }

    public List<Route> routes() {
        return http.routes();
    }

    public Optional<Place> place(String placeName) {
        return places.stream().filter(place -> place.name().equals(placeName)).findFirst();
    }

    public Optional<Transition> transition(String transitionName) {
        return transitions.stream().filter(transition -> transition.name().equals(transitionName)).findFirst();
    }

    public Optional<Sensor> sensor(String sensorName) {
        return sensors.stream().filter(sensor -> sensor.name().equals(sensorName)).findFirst();
    }

    public Optional<Route> route(String routeName) {
        return routes().stream().filter(route -> route.name().equals(routeName)).findFirst();
    }

    /** Capacity of a place, {@link #UNLIMITED} if unknown. */
    public int capacityOf(String placeName) {
        return place(placeName).map(Place::capacity).orElse(UNLIMITED);
    }

    private static void validate(String id, List<Place> places, List<Transition> transitions, List<Sensor> sensors,
                                 HttpInterface http, Image image) {
        List<String> problems = new ArrayList<>();
        Map<String, Place> byName = new LinkedHashMap<>();
        for (Place place : places) {
            if (byName.put(place.name(), place) != null) {
                problems.add("place " + place.name() + " is declared twice");
            }
        }
        Set<String> transitionNames = new HashSet<>();
        for (Transition transition : transitions) {
            if (!transitionNames.add(transition.name())) {
                problems.add("transition " + transition.name() + " is declared twice");
            }
            for (Arc arc : transition.inputs()) {
                checkArc(arc, transition, byName, problems);
            }
            for (Arc arc : transition.outputs()) {
                checkArc(arc, transition, byName, problems);
            }
            for (String inhibitor : transition.inhibitors()) {
                if (!Templates.isTemplate(inhibitor) && !byName.containsKey(inhibitor)) {
                    problems.add("transition " + transition.name() + " is inhibited by unknown place " + inhibitor);
                }
            }
        }
        Set<String> sensorNames = new HashSet<>();
        for (Sensor sensor : sensors) {
            if (!sensorNames.add(sensor.name())) {
                problems.add("sensor " + sensor.name() + " is declared twice");
            }
            if (!byName.containsKey(sensor.place())) {
                problems.add("sensor " + sensor.name() + " reads unknown place " + sensor.place());
            }
        }
        Set<String> routeNames = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        for (Route route : http.routes()) {
            if (!routeNames.add(route.name())) {
                problems.add("route " + route.name() + " is declared twice");
            }
            if (!endpoints.add(route.method().toUpperCase() + " " + route.path())) {
                problems.add("route " + route.name() + ": " + route.method() + " " + route.path() + " is declared twice");
            }
            if (route.command() != null && !byName.containsKey(route.command())) {
                problems.add("route " + route.name() + " puts into unknown place " + route.command());
            }
            for (String transition : route.returnsAfter()) {
                if (!transitionNames.contains(transition)) {
                    problems.add("route " + route.name() + " returns after unknown transition " + transition);
                }
            }
            if (route.reads() != null && !route.reads().contains("${") && !sensorNames.contains(route.reads())) {
                problems.add("route " + route.name() + " reads unknown sensor " + route.reads());
            }
        }
        if (image != null) {
            for (String position : image.positions().keySet()) {
                if (!byName.containsKey(position) && !sensorNames.contains(position)) {
                    problems.add("image position for unknown place or sensor " + position);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Layout " + id + " is invalid: " + String.join("; ", problems));
        }
    }

    private static void checkArc(Arc arc, Transition transition, Map<String, Place> places, List<String> problems) {
        if (!arc.isTemplate() && !places.containsKey(arc.place())) {
            problems.add("transition " + transition.name() + " uses unknown place " + arc.place());
        }
    }

    private static void requireName(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + what + " name");
        }
    }

    private static <V> Map<String, V> unmodifiable(Map<String, V> map) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
