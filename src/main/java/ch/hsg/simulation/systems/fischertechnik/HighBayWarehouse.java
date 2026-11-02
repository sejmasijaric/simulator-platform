package ch.hsg.simulation.systems.fischertechnik;

import ch.hsg.simulation.api.Capability;
import ch.hsg.simulation.api.Component;
import ch.hsg.simulation.api.ParameterType;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.execution.ScheduledChange;
import ch.hsg.simulation.execution.StateChange;
import ch.hsg.simulation.model.ComponentState;
import ch.hsg.simulation.model.EntityView;
import ch.hsg.simulation.runtime.SimulationContext;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * High-bay warehouse, reduced to what the production workflows need: {@code unload} brings a
 * stored workpiece of the requested colour from the rack ({@code stockSlot}) to the pick-up point
 * of the vacuum gripper ({@code outputSlot}). Buckets, slots and storing are not simulated yet.
 */
public record HighBayWarehouse(String getId, String stockSlot, String outputSlot, Duration unloadDuration) implements Component {

    public static final String UNLOAD = "unload";

    @Override
    public String getType() {
        return "high-bay-warehouse";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(Capability.of(UNLOAD, Map.of("color", ParameterType.STRING)));
    }

    @Override
    public ComponentState initialState() {
        return MachineStatus.initial();
    }

    @Override
    public ComponentExecutionResult execute(ComponentAction action, SimulationContext context) {
        if (context.isBusy(getId)) {
            return ComponentExecutionResult.rejected(getId + " is busy");
        }
        String color = action.stringParameter("color");
        Optional<EntityView> workpiece = context.entitiesAt(stockSlot).stream()
                .filter(entity -> color.equals(entity.attributes().get("color")))
                .findFirst();
        if (workpiece.isEmpty()) {
            return ComponentExecutionResult.rejected("No Workpiece with color " + color + " available");
        }
        ComponentState started = MachineStatus.working(context.stateOf(getId),
                "unloading a bucket with a " + color + " workpiece", "transporting the bucket to the conveyor belt");
        return ComponentExecutionResult.accepted(
                ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, started)),
                ScheduledChange.at(unloadDuration, StateChange.move(workpiece.get().id(), outputSlot),
                        StateChange.componentState(getId, MachineStatus.idle(started))));
    }
}
