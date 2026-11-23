package ch.hsg.simulation.adapter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Values for the placeholders in a layout's response bodies and messages. A placeholder is
 * {@code ${name}} or {@code ${name|format}}:
 * <ul>
 *   <li>{@code ${start}}: a request parameter</li>
 *   <li>{@code ${sensor.ov_lb5}}: a sensor as the application reads it now</li>
 *   <li>{@code ${result.sink}}: a result of the transition that ended the request</li>
 *   <li>{@code ${read}}: the sensor a query route reads</li>
 *   <li>{@code ${body}}: the route body, inside the envelope</li>
 *   <li>{@code ${value}}: the offending value, in an error body</li>
 *   <li>{@code ${request.url}}: the request URL without query string</li>
 *   <li>{@code ${time.start|pattern}}, {@code ${time.end|pattern}}, {@code ${now|pattern}}:
 *       wall-clock times in a {@link DateTimeFormatter} pattern</li>
 *   <li>{@code ${time.elapsed|timedelta}}: the elapsed time as Python prints a timedelta</li>
 *   <li>{@code ${uuid}}: a random UUID</li>
 * </ul>
 * A known name whose value is null renders as {@code null}; an unknown name is an error in the
 * layout.
 */
public final class TemplateValues implements Function<String, Object> {

    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, Object> parameters = new HashMap<>();
    private Supplier<Map<String, Object>> sensors = Map::of;
    private Map<String, ?> result = Map.of();
    private LocalDateTime start;
    private LocalDateTime end;
    private LocalDateTime now;

    public TemplateValues parameters(Map<String, ?> requestParameters) {
        parameters.putAll(requestParameters);
        return this;
    }

    public TemplateValues sensors(Supplier<Map<String, Object>> readSensors) {
        this.sensors = memoize(readSensors);
        return this;
    }

    public TemplateValues result(Map<String, ?> transitionResult) {
        this.result = transitionResult == null ? Map.of() : transitionResult;
        return this;
    }

    public TemplateValues with(String name, Object value) {
        values.put(name, value == null ? "null" : value);
        return this;
    }

    public TemplateValues times(LocalDateTime started, LocalDateTime ended) {
        this.start = started;
        this.end = ended;
        return this;
    }

    public TemplateValues now(LocalDateTime time) {
        this.now = time;
        return this;
    }

    @Override
    public Object apply(String placeholder) {
        int bar = placeholder.indexOf('|');
        String name = bar < 0 ? placeholder : placeholder.substring(0, bar);
        String format = bar < 0 ? null : placeholder.substring(bar + 1);
        switch (name) {
            case "uuid":
                return UUID.randomUUID().toString();
            case "now":
                return time(now, format);
            case "time.start":
                return time(start, format);
            case "time.end":
                return time(end, format);
            case "time.elapsed":
                return start == null || end == null ? null : pythonTimedelta(Duration.between(start, end));
            default:
                break;
        }
        if (values.containsKey(name)) {
            return values.get(name);
        }
        if (name.startsWith("sensor.")) {
            Map<String, Object> read = sensors.get();
            String sensor = name.substring("sensor.".length());
            return read.containsKey(sensor) ? nullAsText(read.get(sensor)) : null;
        }
        if (name.startsWith("result.")) {
            String key = name.substring("result.".length());
            return result.containsKey(key) ? nullAsText(result.get(key)) : null;
        }
        return parameters.containsKey(name) ? nullAsText(parameters.get(name)) : null;
    }

    private static Object nullAsText(Object value) {
        return value == null ? "null" : value;
    }

    private static String time(LocalDateTime time, String format) {
        if (time == null) {
            return null;
        }
        return format == null ? time.toString() : DateTimeFormatter.ofPattern(format, Locale.ENGLISH).format(time);
    }

    /** Python's {@code str(timedelta)}: {@code H:MM:SS.ffffff}, without fraction when it is zero. */
    public static String pythonTimedelta(Duration duration) {
        long micros = duration.toNanos() / 1_000 % 1_000_000;
        String base = "%d:%02d:%02d".formatted(duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
        return micros == 0 ? base : base + ".%06d".formatted(micros);
    }

    private static <T> Supplier<T> memoize(Supplier<T> supplier) {
        return new Supplier<>() {
            private T value;
            private boolean done;

            @Override
            public synchronized T get() {
                if (!done) {
                    value = supplier.get();
                    done = true;
                }
                return value;
            }
        };
    }
}
