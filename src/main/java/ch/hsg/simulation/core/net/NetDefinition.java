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
 * physically possible, the sensors applications can read, the routes of the layout's own
 * interface and a picture of it. Immutable; loaded once (e.g. from YAML) and shared by all
 * simulations of the layout. The constructor validates that all names refer to each other.
 */
public record NetDefinition(
        String id,
        String name,
        String description,
        List<Place> places,
        List<Transition> transitions,
        List<Sensor> sensors,
        List<Route> routes,
        List<EventTopic> events,
        Image image) {

    /** No capacity limit. */
    public static final int UNLIMITED = 0;

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
     * {@code ${start}}, filled from the parameters of the command token the same transition
     * consumes earlier in its input list. {@code variable} names the token so an output arc can
     * pass the same token on (a workpiece); without a variable an input takes any token and an
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
     * {@code duration} later. Higher {@code priority} wins when transitions compete for a token;
     * ties go to declaration order.
     */
    public record Transition(String name, List<Arc> inputs, List<Arc> outputs, Duration duration, int priority,
                             String module) {
        public Transition {
            requireName(name, "transition");
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            duration = duration == null ? Duration.ZERO : duration;
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Transition " + name + " needs at least one input");
            }
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Transition " + name + ": duration must not be negative");
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
        }

        public static Transition of(String name, List<Arc> inputs, List<Arc> outputs, Duration duration) {
            return new Transition(name, inputs, outputs, duration, 0, null);
        }
    }

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
     * A value an application can read. {@code values} optionally translates the derived value to
     * what the hardware reports, e.g. {@code {true: 0, false: 1}} for a raw light barrier.
     */
    public record Sensor(String name, SensorKind kind, String place, String attribute, Map<String, Object> values) {
        public Sensor {
            requireName(name, "sensor");
            Objects.requireNonNull(kind, "kind");
            requireName(place, "sensor place");
            if (kind == SensorKind.ATTRIBUTE && (attribute == null || attribute.isBlank())) {
                throw new IllegalArgumentException("Sensor " + name + " needs an attribute");
            }
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        public static Sensor occupied(String name, String place) {
            return new Sensor(name, SensorKind.OCCUPIED, place, null, null);
        }
    }

    public enum RouteKind {
        /** Puts a command token into {@code put} and answers after {@code returnsAfter} fired for it. */
        COMMAND,
        /** Reads sensors and the marking; fires nothing. */
        QUERY
    }

    /** One endpoint of the layout's own interface. {@code hook} names an optional adapter function. */
    public record Route(String name, RouteKind kind, String method, String path, String put, String returnsAfter,
                        Duration timeout, String response, String hook) {
        public Route {
            requireName(name, "route");
            Objects.requireNonNull(kind, "kind");
            requireName(method, "route method");
            requireName(path, "route path");
            if (kind == RouteKind.COMMAND) {
                requireName(put, "route " + name + " put");
            }
        }
    }

    /** A message the layout publishes, e.g. an MQTT topic with the full state every 500 ms. */
    public record EventTopic(String protocol, String topic, Duration every, String payload) {
        public EventTopic {
            requireName(protocol, "event protocol");
            requireName(topic, "event topic");
        }
    }

    /** A picture of the layout and where each place is on it, in pixels. */
    public record Image(String url, int width, int height, Map<String, Point> positions) {
        public Image {
            positions = positions == null ? Map.of() : Map.copyOf(positions);
        }
    }

    public record Point(int x, int y) {
    }

    public NetDefinition {
        requireName(id, "layout id");
        name = name == null ? id : name;
        description = description == null ? "" : description;
        places = List.copyOf(places);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        sensors = sensors == null ? List.of() : List.copyOf(sensors);
        routes = routes == null ? List.of() : List.copyOf(routes);
        events = events == null ? List.of() : List.copyOf(events);
        validate(id, places, transitions, sensors, routes, image);
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
        return routes.stream().filter(route -> route.name().equals(routeName)).findFirst();
    }

    /** Capacity of a place, {@link #UNLIMITED} if unknown. */
    public int capacityOf(String placeName) {
        return place(placeName).map(Place::capacity).orElse(UNLIMITED);
    }

    private static void validate(String id, List<Place> places, List<Transition> transitions, List<Sensor> sensors,
                                 List<Route> routes, Image image) {
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
        for (Route route : routes) {
            if (!routeNames.add(route.name())) {
                problems.add("route " + route.name() + " is declared twice");
            }
            if (route.put() != null && !byName.containsKey(route.put())) {
                problems.add("route " + route.name() + " puts into unknown place " + route.put());
            }
            if (route.returnsAfter() != null && !transitionNames.contains(route.returnsAfter())) {
                problems.add("route " + route.name() + " returns after unknown transition " + route.returnsAfter());
            }
        }
        if (image != null) {
            for (String position : image.positions().keySet()) {
                if (!byName.containsKey(position)) {
                    problems.add("image position for unknown place " + position);
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
}
