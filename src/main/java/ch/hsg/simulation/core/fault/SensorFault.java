package ch.hsg.simulation.core.fault;

import java.time.Duration;
import java.util.Objects;

/**
 * A broken sensor. It changes only what is read, never the marking: the workpiece is still
 * there, the application just doesn't see it correctly.
 */
public record SensorFault(Type type, Object value, Duration delay) {

    public enum Type {
        /** keeps the value it had when the fault was switched on */
        STUCK,
        /** no signal: the value reads as null */
        DROPOUT,
        /** reports the value it had {@code delay} ago */
        DELAYED,
        /** reports {@code value}, or the opposite of a boolean if no value is given */
        WRONG_VALUE
    }

    public SensorFault {
        Objects.requireNonNull(type, "type");
        if (type == Type.DELAYED && (delay == null || delay.isNegative() || delay.isZero())) {
            throw new IllegalArgumentException("A delayed sensor needs a positive delay");
        }
    }

    public static SensorFault stuck() {
        return new SensorFault(Type.STUCK, null, null);
    }

    public static SensorFault dropout() {
        return new SensorFault(Type.DROPOUT, null, null);
    }

    public static SensorFault delayed(Duration delay) {
        return new SensorFault(Type.DELAYED, null, delay);
    }

    public static SensorFault wrongValue(Object value) {
        return new SensorFault(Type.WRONG_VALUE, value, null);
    }
}
