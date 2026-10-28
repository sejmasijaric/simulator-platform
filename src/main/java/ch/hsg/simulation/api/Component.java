package ch.hsg.simulation.api;

import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.model.ComponentState;
import ch.hsg.simulation.runtime.SimulationContext;

import java.util.Optional;
import java.util.Set;

/**
 * A simulated hardware component. Implementations hold behavior and configuration only; the
 * component's mutable state is owned by the {@code Simulation} and changed exclusively through
 * {@link ch.hsg.simulation.execution.StateChange}s returned from {@link #execute}.
 */
public interface Component {

    int UNLIMITED_CAPACITY = Integer.MAX_VALUE;

    String getId();

    String getType();

    Set<Capability> getCapabilities();

    /** Faults this component can simulate. */
    default Set<FaultMode> getFaultModes() {
        return Set.of();
    }

    /** Maximum number of entities located at this component at the same time. */
    default int getCapacity() {
        return UNLIMITED_CAPACITY;
    }

    default ComponentState initialState() {
        return ComponentState.empty();
    }

    /**
     * Decides what the action does. Capability name and parameters are already validated by the
     * engine. Must not mutate anything; read the current state through the context.
     */
    default ComponentExecutionResult execute(ComponentAction action, SimulationContext context) {
        return ComponentExecutionResult.rejected("Component " + getId() + " has no executable capabilities");
    }

    /**
     * Called after every commit. Sensor-like components return their newly observed state (e.g. a
     * light barrier reading whether its slot is occupied); return empty to keep the current state.
     */
    default Optional<ComponentState> observe(SimulationContext context) {
        return Optional.empty();
    }

    default Optional<Capability> findCapability(String name) {
        return getCapabilities().stream().filter(capability -> capability.name().equals(name)).findFirst();
    }
}
