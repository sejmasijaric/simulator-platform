package ch.hsg.simulation.core.fault;

import java.util.Objects;

/** A broken part of the factory itself, attached to one transition. */
public record NetFault(Type type, double factor, String place) {

    public enum Type {
        /** the transition never fires: the machine is broken, requests wait */
        BLOCKED,
        /** the transition takes {@code factor} times as long */
        DELAYED,
        /** the transition runs, but the tokens it passes on disappear (a dropped workpiece) */
        TOKEN_LOSS,
        /** the tokens it passes on end up in {@code place} instead (a misrouted workpiece) */
        MISROUTE
    }

    public NetFault {
        Objects.requireNonNull(type, "type");
        if (type == Type.DELAYED && factor <= 0) {
            throw new IllegalArgumentException("A delay factor must be positive");
        }
        if (type == Type.MISROUTE && (place == null || place.isBlank())) {
            throw new IllegalArgumentException("A misroute needs a target place");
        }
    }

    public static NetFault blocked() {
        return new NetFault(Type.BLOCKED, 1, null);
    }

    public static NetFault delayed(double factor) {
        return new NetFault(Type.DELAYED, factor, null);
    }

    public static NetFault tokenLoss() {
        return new NetFault(Type.TOKEN_LOSS, 1, null);
    }

    public static NetFault misroute(String place) {
        return new NetFault(Type.MISROUTE, 1, place);
    }
}
