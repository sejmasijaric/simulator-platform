package ch.hsg.simulation.core.fault;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The faults switched on in one simulation, on two levels: sensor faults filter what is read,
 * net faults change how transitions fire. Generic: no layout declares its own faults. Not
 * thread-safe; only the engine loop touches it.
 */
public final class Faults {

    private record Sample(Duration time, Object value) {
    }

    private final Map<String, SensorFault> sensorFaults = new LinkedHashMap<>();
    private final Map<String, NetFault> netFaults = new LinkedHashMap<>();
    private final Map<String, Object> stuckValues = new HashMap<>();
    private final Map<String, Deque<Sample>> history = new HashMap<>();

    // ---- sensor level ----

    /** {@code current} is the sensor's value right now, kept for a stuck sensor. */
    public void setSensorFault(String sensor, SensorFault fault, Object current, Duration now) {
        sensorFaults.put(sensor, fault);
        stuckValues.remove(sensor);
        history.remove(sensor);
        if (fault.type() == SensorFault.Type.STUCK) {
            stuckValues.put(sensor, current);
        }
        if (fault.type() == SensorFault.Type.DELAYED) {
            Deque<Sample> samples = new ArrayDeque<>();
            samples.add(new Sample(now, current));
            history.put(sensor, samples);
        }
    }

    public void clearSensorFault(String sensor) {
        sensorFaults.remove(sensor);
        stuckValues.remove(sensor);
        history.remove(sensor);
    }

    public boolean hasDelayedSensors() {
        return !history.isEmpty();
    }

    /** Remembers the true values of delayed sensors after a change, so they can be replayed late. */
    public void record(Duration now, Map<String, Object> trueValues) {
        history.forEach((sensor, samples) -> {
            Object value = trueValues.get(sensor);
            Sample last = samples.peekLast();
            if (last == null || !java.util.Objects.equals(last.value(), value)) {
                samples.addLast(new Sample(now, value));
            }
        });
    }

    /** The values an application reads: the true values with every active sensor fault applied. */
    public Map<String, Object> apply(Duration now, Map<String, Object> trueValues) {
        if (sensorFaults.isEmpty()) {
            return trueValues;
        }
        Map<String, Object> read = new LinkedHashMap<>(trueValues);
        sensorFaults.forEach((sensor, fault) -> {
            if (!read.containsKey(sensor)) {
                return;
            }
            switch (fault.type()) {
                case STUCK -> read.put(sensor, stuckValues.get(sensor));
                case DROPOUT -> read.put(sensor, null);
                case WRONG_VALUE -> read.put(sensor, fault.value() != null ? fault.value() : opposite(read.get(sensor)));
                case DELAYED -> read.put(sensor, delayedValue(sensor, now.minus(fault.delay()), read.get(sensor)));
            }
        });
        return read;
    }

    private Object delayedValue(String sensor, Duration at, Object fallback) {
        Deque<Sample> samples = history.get(sensor);
        if (samples == null || samples.isEmpty()) {
            return fallback;
        }
        // drop samples superseded before "at"; the first one left is the value in force at "at"
        while (samples.size() > 1) {
            Iterator<Sample> iterator = samples.iterator();
            iterator.next();
            if (iterator.next().time().compareTo(at) <= 0) {
                samples.pollFirst();
            } else {
                break;
            }
        }
        return samples.peekFirst().value();
    }

    private static Object opposite(Object value) {
        if (value instanceof Boolean bool) {
            return !bool;
        }
        if (value instanceof Number number) {
            return number.intValue() == 0 ? 1 : 0;
        }
        return value;
    }

    // ---- net level ----

    public void setNetFault(String transition, NetFault fault) {
        netFaults.put(transition, fault);
    }

    public void clearNetFault(String transition) {
        netFaults.remove(transition);
    }

    public boolean isBlocked(String transition) {
        return has(transition, NetFault.Type.BLOCKED);
    }

    public double durationFactor(String transition) {
        NetFault fault = netFaults.get(transition);
        return fault != null && fault.type() == NetFault.Type.DELAYED ? fault.factor() : 1.0;
    }

    public boolean losesTokens(String transition) {
        return has(transition, NetFault.Type.TOKEN_LOSS);
    }

    public Optional<String> misrouteTarget(String transition) {
        NetFault fault = netFaults.get(transition);
        return fault != null && fault.type() == NetFault.Type.MISROUTE ? Optional.of(fault.place()) : Optional.empty();
    }

    private boolean has(String transition, NetFault.Type type) {
        NetFault fault = netFaults.get(transition);
        return fault != null && fault.type() == type;
    }

    // ---- view ----

    public Map<String, SensorFault> sensorFaults() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sensorFaults));
    }

    public Map<String, NetFault> netFaults() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(netFaults));
    }

    public void clear() {
        sensorFaults.clear();
        netFaults.clear();
        stuckValues.clear();
        history.clear();
    }
}
