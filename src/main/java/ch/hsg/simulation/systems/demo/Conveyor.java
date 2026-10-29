package ch.hsg.simulation.systems.demo;

import ch.hsg.simulation.api.Capability;
import ch.hsg.simulation.api.Component;
import ch.hsg.simulation.api.FaultMode;
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
 * Belt from {@code startSlot} to {@code endSlot}. Capability {@code run} moves the entity at the
 * start to the end after {@code runDuration}. Fault JAMMED: the belt runs but the entity stays.
 * State: {@code running} (boolean).
 */
public record Conveyor(String getId, String startSlot, String endSlot, Duration runDuration) implements Component {

    public static final String RUN = "run";
    public static final String JAMMED = "JAMMED";

    @Override
    public String getType() {
        return "conveyor";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(Capability.of(RUN));
    }

    @Override
    public Set<FaultMode> getFaultModes() {
        return Set.of(new FaultMode(JAMMED, "Belt runs, workpiece does not move"));
    }

    @Override
    public ComponentState initialState() {
        return ComponentState.of(Map.of("running", false));
    }

    @Override
    public ComponentExecutionResult execute(ComponentAction action, SimulationContext context) {
        if (context.isBusy(getId)) {
            return ComponentExecutionResult.rejected(getId + " is busy");
        }
        Optional<EntityView> item = context.entitiesAt(startSlot).stream().findFirst();
        List<StateChange> end = new ArrayList<>();
        if (item.isPresent() && !context.isFaultActive(getId, JAMMED)) {
            end.add(StateChange.move(item.get().id(), endSlot));
        }
        end.add(StateChange.componentState(getId, context.stateOf(getId).with("running", false)));
        return ComponentExecutionResult.accepted(
                ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, context.stateOf(getId).with("running", true))),
                ScheduledChange.at(runDuration, end, List.of()));
    }
}
