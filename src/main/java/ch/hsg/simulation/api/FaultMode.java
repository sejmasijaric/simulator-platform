package ch.hsg.simulation.api;

import java.util.Objects;

/**
 * A failure a component can be put into for testing, e.g. {@code ITEM_NOT_MOVED} or
 * {@code SENSOR_STUCK}. The core only stores which faults are active; what a fault does is
 * implemented by the layout-specific component, which checks
 * {@code SimulationContext.isFaultActive(...)} and must only change what the physical
 * component would observably do.
 */
public record FaultMode(String id, String description) {

    public FaultMode {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Fault id must not be blank");
        }
        description = description != null ? description : "";
    }
}
