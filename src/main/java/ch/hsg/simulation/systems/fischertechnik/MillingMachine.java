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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Milling machine: {@code mill} turns the workpiece at {@code initialSlot} to the mill and mills
 * for {@code time} seconds. With {@code end=ejection} it then pushes the workpiece onto its belt
 * ({@code beltSlot}), which carries it to the sorting machine ({@code ejectionTarget}). With
 * {@code end=initial} the workpiece returns. Any other start/end pair does nothing, as on the
 * physical machine.
 */
public record MillingMachine(String getId, String initialSlot, String beltSlot, String ejectionTarget,
                             Duration turnDuration, Duration beltDuration) implements Component {

    public static final String MILL = "mill";
    public static final String INITIAL = "initial";
    public static final String EJECTION = "ejection";

    @Override
    public String getType() {
        return "milling-machine";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(Capability.of(MILL, Map.of(
                "start", ParameterType.STRING, "end", ParameterType.STRING, "time", ParameterType.INTEGER)));
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
        long time = ((Number) action.parameters().get("time")).longValue();
        if (time <= 0) {
            return ComponentExecutionResult.rejected("Time must be more than 0");
        }
        String start = action.stringParameter("start");
        String end = action.stringParameter("end");
        boolean eject = INITIAL.equals(start) && EJECTION.equals(end);
        boolean back = INITIAL.equals(start) && INITIAL.equals(end);
        if (!eject && !back) {
            return ComponentExecutionResult.completed(List.of(), List.of());
        }

        Optional<EntityView> workpiece = context.entitiesAt(initialSlot).stream().findFirst();
        Duration milled = turnDuration.plusSeconds(time);
        ComponentState started = MachineStatus.working(context.stateOf(getId),
                "transporting the workpiece from the " + start + " to the mill, milling it for " + time
                        + " seconds and transporting it to the " + end + " afterwards",
                "transporting the workpiece to the mill");
        List<ScheduledChange> phases = new ArrayList<>();
        phases.add(ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, started)));
        phases.add(ScheduledChange.at(turnDuration, StateChange.componentState(getId,
                started.with(MachineStatus.SUB_TASK, "milling the workpiece for " + time + " seconds"))));
        if (back) {
            phases.add(ScheduledChange.at(milled, StateChange.componentState(getId,
                    started.with(MachineStatus.SUB_TASK, "transporting the workpiece to the initial position"))));
            phases.add(ScheduledChange.at(milled.plus(turnDuration), StateChange.componentState(getId, MachineStatus.idle(started))));
            return ComponentExecutionResult.accepted(phases);
        }

        List<StateChange> onBelt = new ArrayList<>();
        List<StateChange> delivered = new ArrayList<>();
        workpiece.ifPresent(entity -> {
            onBelt.add(StateChange.move(entity.id(), beltSlot));
            delivered.add(StateChange.move(entity.id(), ejectionTarget));
        });
        onBelt.add(StateChange.componentState(getId, started.with(MachineStatus.SUB_TASK, "transporting the workpiece to the sorting machine")));
        delivered.add(StateChange.componentState(getId, started.with(MachineStatus.SUB_TASK, "moving towards the initial position")));
        Duration ejected = milled.plus(turnDuration);
        phases.add(ScheduledChange.at(milled, StateChange.componentState(getId,
                started.with(MachineStatus.SUB_TASK, "transporting the workpiece to the ejection position"))));
        phases.add(ScheduledChange.at(ejected, onBelt, List.of()));
        phases.add(ScheduledChange.at(ejected.plus(beltDuration), delivered, List.of()));
        phases.add(ScheduledChange.at(ejected.plus(beltDuration).plus(turnDuration),
                StateChange.componentState(getId, MachineStatus.idle(started))));
        return ComponentExecutionResult.accepted(phases);
    }
}
