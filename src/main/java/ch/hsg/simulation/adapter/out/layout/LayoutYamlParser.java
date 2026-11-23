package ch.hsg.simulation.adapter.out.layout;

import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.net.NetDefinition.Arc;
import ch.hsg.simulation.core.net.NetDefinition.ErrorResponse;
import ch.hsg.simulation.core.net.NetDefinition.EventTopic;
import ch.hsg.simulation.core.net.NetDefinition.HttpInterface;
import ch.hsg.simulation.core.net.NetDefinition.Image;
import ch.hsg.simulation.core.net.NetDefinition.Parameter;
import ch.hsg.simulation.core.net.NetDefinition.Place;
import ch.hsg.simulation.core.net.NetDefinition.Point;
import ch.hsg.simulation.core.net.NetDefinition.Route;
import ch.hsg.simulation.core.net.NetDefinition.RouteKind;
import ch.hsg.simulation.core.net.NetDefinition.Sensor;
import ch.hsg.simulation.core.net.NetDefinition.SensorKind;
import ch.hsg.simulation.core.net.NetDefinition.TokenTemplate;
import ch.hsg.simulation.core.net.NetDefinition.Transition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one layout file into a {@link NetDefinition}. The format is documented in
 * {@code docs/layouts/layout-format.md}. Unknown keys are errors (typos would otherwise be
 * silently ignored), except top-level keys starting with {@code x-}, which hold YAML anchors
 * for reuse, e.g. shared error responses.
 */
public final class LayoutYamlParser {

    private static final Pattern SHORT_DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)");

    /** Parses {@code yaml}; {@code source} names the file in error messages. */
    public NetDefinition parse(String yaml, String source) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(1000); // layouts reuse shared error responses a lot
            Object document = new Yaml(new SafeConstructor(options)).load(yaml);
            return layout(map(document, "layout"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(source + ": " + exception.getMessage(), exception);
        }
    }

    private NetDefinition layout(Map<String, Object> yaml) {
        allowOnly(yaml, "layout", Set.of("id", "name", "description", "image", "tokens", "places", "transitions",
                "sensors", "http", "events"), true);
        return new NetDefinition(
                string(yaml.get("id"), "id"),
                optionalString(yaml.get("name")),
                optionalString(yaml.get("description")),
                places(map(yaml.get("places"), "places")),
                transitions(optionalMap(yaml.get("transitions"), "transitions")),
                sensors(optionalMap(yaml.get("sensors"), "sensors")),
                http(optionalMap(yaml.get("http"), "http")),
                events(optionalList(yaml.get("events"), "events")),
                image(optionalMap(yaml.get("image"), "image")),
                tokens(optionalMap(yaml.get("tokens"), "tokens")));
    }

    // ---- net ----

    private List<Place> places(Map<String, Object> yaml) {
        List<Place> places = new ArrayList<>();
        yaml.forEach((name, value) -> {
            Map<String, Object> place = optionalMap(value, "place " + name);
            allowOnly(place, "place " + name, Set.of("capacity", "initial", "module"), false);
            places.add(new Place(name, integer(place.getOrDefault("capacity", 0), "capacity"),
                    integer(place.getOrDefault("initial", 0), "initial"), optionalString(place.get("module"))));
        });
        return places;
    }

    private List<Transition> transitions(Map<String, Object> yaml) {
        List<Transition> transitions = new ArrayList<>();
        yaml.forEach((name, value) -> {
            String what = "transition " + name;
            Map<String, Object> transition = map(value, what);
            allowOnly(transition, what, Set.of("in", "out", "duration", "per-unit", "when", "inhibit", "result",
                    "priority", "module"), false);
            String unitParameter = null;
            Duration perUnit = null;
            if (transition.get("per-unit") != null) {
                Map<String, Object> unit = map(transition.get("per-unit"), what + " per-unit");
                allowOnly(unit, what + " per-unit", Set.of("parameter", "duration"), false);
                unitParameter = string(unit.get("parameter"), what + " per-unit parameter");
                perUnit = duration(unit.get("duration"), what + " per-unit duration");
            }
            transitions.add(new Transition(name,
                    arcs(list(transition.get("in"), what + " in"), what),
                    arcs(optionalList(transition.get("out"), what + " out"), what),
                    transition.get("duration") == null ? Duration.ZERO : duration(transition.get("duration"), what),
                    unitParameter, perUnit,
                    strings(optionalMap(transition.get("when"), what + " when")),
                    stringList(optionalList(transition.get("inhibit"), what + " inhibit")),
                    strings(optionalMap(transition.get("result"), what + " result")),
                    integer(transition.getOrDefault("priority", 0), what + " priority"),
                    optionalString(transition.get("module"))));
        });
        return transitions;
    }

    /** {@code place} takes any token, {@code {place: variable}} binds it. */
    private List<Arc> arcs(List<Object> yaml, String what) {
        List<Arc> arcs = new ArrayList<>();
        for (Object item : yaml) {
            if (item instanceof Map<?, ?> single) {
                if (single.size() != 1) {
                    throw new IllegalArgumentException(what + ": an arc is a place or {place: variable}");
                }
                Map.Entry<?, ?> entry = single.entrySet().iterator().next();
                arcs.add(Arc.of(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())));
            } else {
                arcs.add(Arc.of(string(item, what + " arc")));
            }
        }
        return arcs;
    }

    // ---- sensors ----

    private List<Sensor> sensors(Map<String, Object> yaml) {
        List<Sensor> sensors = new ArrayList<>();
        yaml.forEach((name, value) -> {
            String what = "sensor " + name;
            Map<String, Object> sensor = map(value, what);
            allowOnly(sensor, what, Set.of("occupied", "count", "attribute", "place", "values"), false);
            SensorKind kind;
            String place;
            String attribute = null;
            if (sensor.containsKey("occupied")) {
                kind = SensorKind.OCCUPIED;
                place = string(sensor.get("occupied"), what);
            } else if (sensor.containsKey("count")) {
                kind = SensorKind.COUNT;
                place = string(sensor.get("count"), what);
            } else if (sensor.containsKey("attribute")) {
                kind = SensorKind.ATTRIBUTE;
                attribute = string(sensor.get("attribute"), what);
                place = string(sensor.get("place"), what + " place");
            } else {
                throw new IllegalArgumentException(what + " needs occupied, count or attribute");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            optionalMap(sensor.get("values"), what + " values").forEach(values::put);
            sensors.add(new Sensor(name, kind, place, attribute, values));
        });
        return sensors;
    }

    // ---- interface ----

    private HttpInterface http(Map<String, Object> yaml) {
        allowOnly(yaml, "http", Set.of("envelope", "routes"), false);
        List<Route> routes = new ArrayList<>();
        optionalMap(yaml.get("routes"), "http routes").forEach((name, value) -> routes.add(route(name, value)));
        return new HttpInterface(optionalString(yaml.get("envelope")), routes);
    }

    private Route route(String name, Object value) {
        String what = "route " + name;
        Map<String, Object> route = map(value, what);
        allowOnly(route, what, Set.of("get", "post", "put", "delete", "parameters", "command", "returns-after",
                "timeout", "reads", "body"), false);
        String method = null;
        String path = null;
        for (String candidate : List.of("get", "post", "put", "delete")) {
            if (route.containsKey(candidate)) {
                if (method != null) {
                    throw new IllegalArgumentException(what + " has more than one method");
                }
                method = candidate.toUpperCase();
                path = string(route.get(candidate), what + " path");
            }
        }
        if (method == null) {
            throw new IllegalArgumentException(what + " needs a method: get, post, put or delete");
        }
        String command = optionalString(route.get("command"));
        Object endings = route.get("returns-after");
        List<String> returnsAfter = endings == null ? List.of()
                : endings instanceof List<?> ? stringList(list(endings, what + " returns-after"))
                : List.of(string(endings, what + " returns-after"));
        List<Parameter> parameters = new ArrayList<>();
        optionalMap(route.get("parameters"), what + " parameters")
                .forEach((parameter, spec) -> parameters.add(parameter(parameter, spec, what)));
        return new Route(name, command != null ? RouteKind.COMMAND : RouteKind.QUERY, method, path, parameters,
                command, returnsAfter,
                route.get("timeout") == null ? null : duration(route.get("timeout"), what + " timeout"),
                optionalString(route.get("reads")), optionalString(route.get("body")));
    }

    private Parameter parameter(String name, Object value, String route) {
        String what = route + " parameter " + name;
        Map<String, Object> spec = optionalMap(value, what);
        allowOnly(spec, what, Set.of("required", "values", "default", "integer", "missing", "invalid"), false);
        return new Parameter(name,
                Boolean.TRUE.equals(spec.get("required")),
                stringList(optionalList(spec.get("values"), what + " values")),
                spec.get("default") == null ? null : String.valueOf(spec.get("default")),
                Boolean.TRUE.equals(spec.get("integer")),
                error(spec.get("missing"), what + " missing"),
                error(spec.get("invalid"), what + " invalid"));
    }

    private ErrorResponse error(Object value, String what) {
        if (value == null) {
            return null;
        }
        Map<String, Object> error = map(value, what);
        allowOnly(error, what, Set.of("status", "body"), false);
        return new ErrorResponse(integer(error.get("status"), what + " status"), string(error.get("body"), what + " body"));
    }

    /** Each event is {@code {mqtt: topic, every: 500ms, payload: ...}}; the key names the protocol. */
    private List<EventTopic> events(List<Object> yaml) {
        List<EventTopic> events = new ArrayList<>();
        for (Object item : yaml) {
            Map<String, Object> event = map(item, "event");
            String protocol = event.keySet().stream()
                    .filter(key -> !Set.of("every", "payload").contains(key))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("event needs a protocol, e.g. mqtt"));
            allowOnly(event, "event " + protocol, Set.of(protocol, "every", "payload"), false);
            events.add(new EventTopic(protocol, string(event.get(protocol), "event topic"),
                    event.get("every") == null ? null : duration(event.get("every"), "event every"),
                    string(event.get("payload"), "event payload")));
        }
        return events;
    }

    // ---- UI ----

    private Image image(Map<String, Object> yaml) {
        if (yaml.isEmpty()) {
            return null;
        }
        allowOnly(yaml, "image", Set.of("url", "width", "height", "positions"), false);
        Map<String, Point> positions = new LinkedHashMap<>();
        optionalMap(yaml.get("positions"), "image positions").forEach((name, value) -> {
            List<Object> xy = list(value, "position " + name);
            if (xy.size() != 2) {
                throw new IllegalArgumentException("position " + name + " must be [x, y]");
            }
            positions.put(name, new Point(integer(xy.get(0), name + " x"), integer(xy.get(1), name + " y")));
        });
        return new Image(string(yaml.get("url"), "image url"), integer(yaml.get("width"), "image width"),
                integer(yaml.get("height"), "image height"), positions);
    }

    private List<TokenTemplate> tokens(Map<String, Object> yaml) {
        List<TokenTemplate> templates = new ArrayList<>();
        yaml.forEach((name, value) -> {
            Map<String, Object> attributes = new LinkedHashMap<>();
            optionalMap(value, "token " + name).forEach(attributes::put);
            templates.add(new TokenTemplate(name, attributes));
        });
        return templates;
    }

    // ---- values ----

    /** {@code 45s}, {@code 500ms}, {@code 2.4s}, {@code 30m}, {@code 1h}, ISO-8601 ({@code PT45S}) or a number of seconds. */
    static Duration duration(Object value, String what) {
        if (value instanceof Number number) {
            return Duration.ofNanos(Math.round(number.doubleValue() * 1_000_000_000L));
        }
        String text = string(value, what).trim();
        Matcher matcher = SHORT_DURATION.matcher(text);
        if (matcher.matches()) {
            double amount = Double.parseDouble(matcher.group(1));
            long nanosPerUnit = switch (matcher.group(2)) {
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60_000_000_000L;
                default -> 3_600_000_000_000L;
            };
            return Duration.ofNanos(Math.round(amount * nanosPerUnit));
        }
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(what + ": '" + text + "' is not a duration (e.g. 45s, 500ms, PT45S)");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(what + " must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> optionalMap(Object value, String what) {
        return value == null ? Map.of() : map(value, what);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String what) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(what + " must be a list");
        }
        return (List<Object>) list;
    }

    private static List<Object> optionalList(Object value, String what) {
        return value == null ? List.of() : list(value, what);
    }

    private static List<String> stringList(List<Object> values) {
        return values.stream().map(String::valueOf).toList();
    }

    private static Map<String, String> strings(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, String.valueOf(value)));
        return result;
    }

    private static String string(Object value, String what) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(what + " is missing");
        }
        return String.valueOf(value);
    }

    private static String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Object value, String what) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(string(value, what).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(what + " must be a whole number");
        }
    }

    private static void allowOnly(Map<String, Object> yaml, String what, Set<String> keys, boolean allowExtensions) {
        for (String key : yaml.keySet()) {
            if (!keys.contains(key) && !(allowExtensions && key.startsWith("x-"))) {
                throw new IllegalArgumentException(what + ": unknown key '" + key + "'");
            }
        }
    }
}
