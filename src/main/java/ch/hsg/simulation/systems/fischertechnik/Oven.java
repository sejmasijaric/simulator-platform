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
 * Oven: {@code burn} drives the platform with the workpiece from {@code platformSlot} into
 * {@code chamberSlot}, burns and drives it back out. The light barrier at the platform position is
 * therefore free while burning, as on the physical oven. {@code time} is not seconds: burning
 * takes {@code time × 2.4 s} (it blinks {@code time × 8} times at 0.3 s).
 */
public record Oven(String getId, String platformSlot, String chamberSlot, Duration driveDuration) implements Component {

    public static final String BURN = "burn";
    static final Duration PER_TIME_UNIT = Duration.ofMillis(2400);

    @Override
    public String getType() {
        return "oven";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(Capability.of(BURN, Map.of("time", ParameterType.INTEGER)));
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
        Duration burning = PER_TIME_UNIT.multipliedBy(Math.max(time, 0));
        Optional<EntityView> workpiece = context.entitiesAt(platformSlot).stream().findFirst();
        ComponentState started = MachineStatus.working(context.stateOf(getId),
                "burning the workpiece for " + time + " seconds", "transporting the workpiece to the inside of the oven");

        // The workpiece is back on the platform (light barrier interrupted) only when the drive out has finished.
        List<StateChange> inside = new ArrayList<>();
        List<StateChange> outside = new ArrayList<>();
        workpiece.ifPresent(entity -> {
            inside.add(StateChange.move(entity.id(), chamberSlot));
            outside.add(StateChange.move(entity.id(), platformSlot));
        });
        inside.add(StateChange.componentState(getId, started.with(MachineStatus.SUB_TASK, "burning the workpiece for " + time + " seconds")));
        outside.add(StateChange.componentState(getId, MachineStatus.idle(started)));
        return ComponentExecutionResult.accepted(
                ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, started)),
                ScheduledChange.at(driveDuration, inside, List.of()),
                ScheduledChange.at(driveDuration.plus(burning), StateChange.componentState(getId,
                        started.with(MachineStatus.SUB_TASK, "transporting the workpiece to the outside of the oven"))),
                ScheduledChange.at(driveDuration.multipliedBy(2).plus(burning), outside, List.of()));
    }
}
