package ch.hsg.simulation.systems.fischertechnik;

import ch.hsg.simulation.api.Capability;
import ch.hsg.simulation.api.Component;
import ch.hsg.simulation.api.ParameterType;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.execution.ScheduledChange;
import ch.hsg.simulation.execution.StateChange;
import ch.hsg.simulation.model.ComponentState;
import ch.hsg.simulation.runtime.SimulationContext;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Oven: {@code burn} drives the platform in, burns and drives it out. The workpiece stays on the
 * platform position. As on the physical oven, {@code time} is not seconds: burning takes
 * {@code time × 2.4 s} (it blinks {@code time × 8} times at 0.3 s).
 */
public record Oven(String getId, Duration driveDuration) implements Component {

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
        ComponentState started = MachineStatus.working(context.stateOf(getId),
                "burning the workpiece for " + time + " seconds", "transporting the workpiece to the inside of the oven");
        return ComponentExecutionResult.accepted(
                ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, started)),
                ScheduledChange.at(driveDuration, StateChange.componentState(getId,
                        started.with(MachineStatus.SUB_TASK, "burning the workpiece for " + time + " seconds"))),
                ScheduledChange.at(driveDuration.plus(burning), StateChange.componentState(getId,
                        started.with(MachineStatus.SUB_TASK, "transporting the workpiece to the outside of the oven"))),
                ScheduledChange.at(driveDuration.multipliedBy(2).plus(burning), StateChange.componentState(getId,
                        MachineStatus.idle(started))));
    }
}
