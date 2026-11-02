package ch.hsg.simulation.systems.fischertechnik;

import ch.hsg.simulation.model.ComponentState;

import java.util.Map;

/**
 * Status fields every factory machine reports, named like the physical MQTT payload:
 * {@code current_state} ({@code ready} / {@code not ready}), {@code current_task} and
 * {@code current_sub_task}.
 */
final class MachineStatus {

    static final String STATE = "current_state";
    static final String TASK = "current_task";
    static final String SUB_TASK = "current_sub_task";
    static final String READY = "ready";
    static final String NOT_READY = "not ready";

    private MachineStatus() {
    }

    static ComponentState initial() {
        return ComponentState.of(Map.of(STATE, READY, TASK, "", SUB_TASK, ""));
    }

    static ComponentState working(ComponentState current, String task, String subTask) {
        return current.with(Map.of(STATE, NOT_READY, TASK, task, SUB_TASK, subTask));
    }

    static ComponentState idle(ComponentState current) {
        return current.with(Map.of(STATE, READY, TASK, "", SUB_TASK, ""));
    }
}
