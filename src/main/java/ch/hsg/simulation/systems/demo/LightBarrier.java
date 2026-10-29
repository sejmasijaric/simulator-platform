package ch.hsg.simulation.systems.demo;

import ch.hsg.simulation.api.Capability;
import ch.hsg.simulation.api.Component;
import ch.hsg.simulation.api.FaultMode;
import ch.hsg.simulation.model.ComponentState;
import ch.hsg.simulation.runtime.SimulationContext;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Sensor reporting whether its slot is occupied. Fault SENSOR_STUCK freezes the last value. */
public record LightBarrier(String getId, String observedSlot) implements Component {

    public static final String SENSOR_STUCK = "SENSOR_STUCK";

    @Override
    public String getType() {
        return "light-barrier";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of();
    }

    @Override
    public Set<FaultMode> getFaultModes() {
        return Set.of(new FaultMode(SENSOR_STUCK, "Keeps reporting its last value"));
    }

    @Override
    public ComponentState initialState() {
        return ComponentState.of(Map.of("interrupted", false));
    }

    @Override
    public Optional<ComponentState> observe(SimulationContext context) {
        if (context.isFaultActive(getId, SENSOR_STUCK)) {
            return Optional.empty();
        }
        return Optional.of(ComponentState.of(Map.of("interrupted", !context.entitiesAt(observedSlot).isEmpty())));
    }
}
