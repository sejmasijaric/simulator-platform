package ch.hsg.simulation.systems.fischertechnik;

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
 * Vacuum gripper (VGR) or workstation transport (WT): {@code pick_up_and_transport} carries the
 * workpiece at {@code start} to {@code end}. It is held by the machine itself in between. Like the
 * physical machine, it runs even when {@code start} is empty. Fault MISSES_WORKPIECE: the task runs
 * but the workpiece stays.
 */
public record Transport(String getId, String getType, Set<String> starts, Set<String> ends,
                        Duration pickUpAfter, Duration duration) implements Component {

    public static final String PICK_UP_AND_TRANSPORT = "pick_up_and_transport";
    public static final String MISSES_WORKPIECE = "MISSES_WORKPIECE";

    public Transport {
        starts = Set.copyOf(starts);
        ends = Set.copyOf(ends);
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(Capability.of(PICK_UP_AND_TRANSPORT, Map.of("start", ParameterType.STRING, "end", ParameterType.STRING)));
    }

    @Override
    public Set<FaultMode> getFaultModes() {
        return Set.of(new FaultMode(MISSES_WORKPIECE, "Task runs but the workpiece stays where it is"));
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
        String start = action.stringParameter("start");
        String end = action.stringParameter("end");
        if (start.equals(end)) {
            return ComponentExecutionResult.rejected("Position " + start + " and " + end + " have to be different and also can't be None.");
        }
        if (!starts.contains(start)) {
            return ComponentExecutionResult.rejected("Position " + start + " does not exist");
        }
        if (!ends.contains(end)) {
            return ComponentExecutionResult.rejected("Position " + end + " does not exist");
        }

        Optional<EntityView> workpiece = context.entitiesAt(start).stream().findFirst();
        boolean moves = workpiece.isPresent() && !context.isFaultActive(getId, MISSES_WORKPIECE);
        ComponentState started = MachineStatus.working(context.stateOf(getId),
                "picking up and transporting the workpiece from the " + start + " to the " + end,
                "moving towards the " + start);

        List<StateChange> pickUp = new ArrayList<>();
        List<StateChange> dropOff = new ArrayList<>();
        if (moves) {
            pickUp.add(StateChange.move(workpiece.get().id(), getId));
            dropOff.add(StateChange.move(workpiece.get().id(), end));
        }
        pickUp.add(StateChange.componentState(getId, started.with(MachineStatus.SUB_TASK, "transporting the workpiece to the " + end)));
        dropOff.add(StateChange.componentState(getId, MachineStatus.idle(started)));
        return ComponentExecutionResult.accepted(
                ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, started)),
                ScheduledChange.at(pickUpAfter, pickUp, List.of()),
                ScheduledChange.at(duration, dropOff, List.of()));
    }
}
