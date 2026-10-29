package ch.hsg.simulation.systems.demo;

import ch.hsg.simulation.api.Capability;
import ch.hsg.simulation.api.Component;
import ch.hsg.simulation.api.FaultMode;
import ch.hsg.simulation.api.ParameterType;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.execution.ScheduledChange;
import ch.hsg.simulation.execution.StateChange;
import ch.hsg.simulation.model.ComponentState;
import ch.hsg.simulation.model.EntityView;
import ch.hsg.simulation.runtime.SimulationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Two-phase transport: after one phase the entity sits in the hold slot, after the second at the
 * target. Fault ITEM_NOT_MOVED keeps the task running but silently skips the moves.
 */
public record Transporter(String getId, String holdSlot, Duration phaseDuration) implements Component {

    public static final String TRANSPORT = "transport";
    public static final String ITEM_NOT_MOVED = "ITEM_NOT_MOVED";

    @Override
    public String getType() {
        return "transporter";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(Capability.of(TRANSPORT, Map.of("from", ParameterType.STRING, "to", ParameterType.STRING)));
    }

    @Override
    public Set<FaultMode> getFaultModes() {
        return Set.of(new FaultMode(ITEM_NOT_MOVED, "Task runs but the item stays where it is"));
    }

    @Override
    public ComponentState initialState() {
        return ComponentState.of(Map.of("currentTask", ""));
    }

    @Override
    public ComponentExecutionResult execute(ComponentAction action, SimulationContext context) {
        if (context.isBusy(getId)) {
            return ComponentExecutionResult.rejected(getId + " is busy");
        }
        String from = action.stringParameter("from");
        String to = action.stringParameter("to");
        Optional<EntityView> item = context.entitiesAt(from).stream().findFirst();
        boolean moves = item.isPresent() && !context.isFaultActive(getId, ITEM_NOT_MOVED);

        ComponentState busy = context.stateOf(getId).with("currentTask", TRANSPORT);
        ComponentState idle = context.stateOf(getId).with("currentTask", "");

        List<StateChange> pickUp = new ArrayList<>();
        List<StateChange> dropOff = new ArrayList<>();
        if (moves) {
            pickUp.add(StateChange.move(item.get().id(), holdSlot));
            dropOff.add(StateChange.move(item.get().id(), to));
        }
        dropOff.add(StateChange.componentState(getId, idle));
        return ComponentExecutionResult.accepted(
                ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, busy)),
                ScheduledChange.at(phaseDuration, pickUp, List.of()),
                ScheduledChange.at(phaseDuration.multipliedBy(2), dropOff, List.of()));
    }
}
