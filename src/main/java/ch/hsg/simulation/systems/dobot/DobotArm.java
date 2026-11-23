package ch.hsg.simulation.systems.dobot;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dobot Magician with suction cup, running the lab program ({@code dobot.py}): moves between
 * stone positions, the factory handover and free coordinates. Each move goes straight to the
 * target pose and reports the new pose.
 *
 * <p>Suction picks up the entity at the place whose pose matches the arm's pose (x, y and z
 * within {@link #TOLERANCE_MM}). Releasing drops the held entity at the free place below the arm
 * (x and y within tolerance), or on {@code fallbackPlace} if there is none.
 *
 * <p>Capabilities take the lab API's {@code start} / {@code end} strings (see {@link Target}) and
 * reproduce its sequences, including two quirks of the lab code:
 * <ul>
 *   <li>{@code move_to} with {@code end=factory} moves to {@code start} and stops there; with
 *       {@code start=factory} it is rejected.</li>
 *   <li>{@code pick_up_and_transport} with {@code start=factory} drives into the factory and back
 *       with the suction off and switches it on only at the home position, so it picks nothing.</li>
 * </ul>
 * Fault MISSES_STONE: suction runs but does not grip. State: {@code x}, {@code y}, {@code z},
 * {@code r}, {@code suction} and the {@code current_task} / {@code current_sub_task} shown in the UI.
 */
public record DobotArm(String getId, Map<String, Pose> places, String fallbackPlace, Timing timing) implements Component {

    public static final String MOVE_TO = "move_to";
    public static final String PICK_UP_AND_TRANSPORT = "pick_up_and_transport";
    public static final String RUN_PLAYBACK = "run_playback";
    public static final String MISSES_STONE = "MISSES_STONE";
    public static final double TOLERANCE_MM = 10;

    /** Stone positions of the lab program. */
    public static final Map<String, Pose> STONE_POSITIONS = stonePositions();
    /** {@code move_to_home_position}. */
    public static final Pose HOME = new Pose(182.0821, -3.4211, 38.5537, 0);
    /** Where {@code move_to_standard_placing} releases the stone into the factory. */
    public static final Pose FACTORY_DROP = new Pose(2.2468, 295.7190, 35.6211, 127.5453);

    private static final Pose FACTORY_APPROACH_1 = new Pose(244.8616, 57.5479, 44.9715, -134.9775);
    private static final Pose FACTORY_APPROACH_2 = new Pose(13.8968, 190.8185, 54.4975, 123.8153);
    private static final Pose ABOVE_FACTORY = new Pose(2.2468, 295.7189, 50.6452, 127.5453);

    /**
     * How long moves take: {@code overhead + distance / speed} per move, {@code homing} for the
     * calibration run of a playback.
     */
    public record Timing(Duration moveOverhead, double speedMmPerSecond, Duration homing) {

        Duration travel(Pose from, Pose to) {
            return moveOverhead.plusMillis(Math.round(from.distanceTo(to) / speedMmPerSecond * 1000));
        }
    }

    public DobotArm {
        places = Map.copyOf(places);
    }

    @Override
    public String getType() {
        return "robot-arm";
    }

    @Override
    public Set<Capability> getCapabilities() {
        Map<String, ParameterType> startEnd = Map.of("start", ParameterType.STRING, "end", ParameterType.STRING);
        return Set.of(Capability.of(MOVE_TO, startEnd), Capability.of(PICK_UP_AND_TRANSPORT, startEnd),
                Capability.of(RUN_PLAYBACK, Map.of("playback", ParameterType.STRING)));
    }

    @Override
    public Set<FaultMode> getFaultModes() {
        return Set.of(new FaultMode(MISSES_STONE, "Suction runs but does not pick up the stone"));
    }

    @Override
    public ComponentState initialState() {
        return state(HOME, false, "", "");
    }

    @Override
    public ComponentExecutionResult execute(ComponentAction action, SimulationContext context) {
        if (context.isBusy(getId)) {
            return ComponentExecutionResult.rejected(getId + " is busy");
        }
        try {
            return switch (action.capability()) {
                case MOVE_TO -> moveTo(target(action, "start"), target(action, "end"), context);
                case PICK_UP_AND_TRANSPORT -> pickUpAndTransport(target(action, "start"), target(action, "end"), context);
                case RUN_PLAYBACK -> runPlayback(action.stringParameter("playback"), context);
                default -> ComponentExecutionResult.rejected("Unknown capability " + action.capability());
            };
        } catch (IllegalArgumentException exception) {
            return ComponentExecutionResult.rejected(exception.getMessage());
        }
    }

    private ComponentExecutionResult moveTo(Target start, Target end, SimulationContext context) {
        if (start.isFactory()) {
            throw new IllegalArgumentException("too many values to unpack (expected 4)");
        }
        Plan plan = new Plan(context, "moving from " + start.input() + " to " + end.input());
        plan.move(start.pose(), start.input());
        if (!end.isFactory()) {
            plan.move(end.pose(), end.input());
        }
        return plan.finish();
    }

    private ComponentExecutionResult pickUpAndTransport(Target start, Target end, SimulationContext context) {
        Plan plan = new Plan(context, "picking up a stone at " + start.input() + " and bringing it to " + end.input());
        if (start.isFactory()) {
            plan.move(ABOVE_FACTORY, "above the factory");
            plan.move(FACTORY_DROP, "the factory");
            plan.move(ABOVE_FACTORY, "above the factory");
            plan.move(FACTORY_APPROACH_2, "the factory approach");
            plan.move(FACTORY_APPROACH_1, "the factory approach");
            plan.move(HOME, "home");
        } else {
            plan.move(start.pose(), start.input());
        }
        plan.suction(true);
        if (end.isFactory()) {
            plan.move(HOME, "home");
            plan.move(FACTORY_APPROACH_1, "the factory approach");
            plan.move(FACTORY_APPROACH_2, "the factory approach");
            plan.move(ABOVE_FACTORY, "above the factory");
            plan.move(FACTORY_DROP, "the factory");
            plan.suction(false);
            plan.move(ABOVE_FACTORY, "above the factory");
            plan.move(HOME, "home");
        } else {
            plan.move(end.pose(), end.input());
            plan.suction(false);
        }
        return plan.finish();
    }

    /** Calibrates, moves home, runs the moves and returns home. Invalid XML stops after the first move home. */
    private ComponentExecutionResult runPlayback(String xml, SimulationContext context) {
        Plan plan = new Plan(context, "running a playback");
        plan.home();
        plan.move(HOME, "home");
        List<Pose> moves;
        try {
            moves = Playback.parse(xml == null ? "" : xml);
        } catch (IllegalArgumentException exception) {
            return plan.finish();
        }
        for (int i = 0; i < moves.size(); i++) {
            plan.move(moves.get(i), "playback point " + (i + 1) + " of " + moves.size());
        }
        plan.move(HOME, "home");
        return plan.finish();
    }

    private Target target(ComponentAction action, String name) {
        String value = action.stringParameter(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Both start and end parameters are required");
        }
        return Target.parse(value, STONE_POSITIONS);
    }

    private static ComponentState state(Pose pose, boolean suction, String task, String subTask) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("x", pose.x());
        attributes.put("y", pose.y());
        attributes.put("z", pose.z());
        attributes.put("r", pose.r());
        attributes.put("suction", suction);
        attributes.put("current_task", task);
        attributes.put("current_sub_task", subTask);
        return ComponentState.of(attributes);
    }

    private static Map<String, Pose> stonePositions() {
        Map<String, Pose> positions = new LinkedHashMap<>();
        positions.put("pos1", new Pose(248.3179, 60.3962, -80.2133, -135.4775));
        positions.put("pos2", new Pose(272.3332, 63.7447, -73.8629, -134.4575));
        positions.put("pos3", new Pose(245.3788, 89.3134, -73.6323, -127.9375));
        positions.put("pos4", new Pose(270.5498, 89.5389, -74.2002, -128.9675));
        positions.put("pos5", new Pose(244.3448, 115.0324, -74.6829, -121.4675));
        positions.put("pos6", new Pose(270.0023, 114.7764, -75.2266, -123.6475));
        positions.put("pos7", new Pose(243.6652, 139.5487, -74.7541, -116.8775));
        positions.put("pos8", new Pose(268.6874, 140.1683, -76.0964, -119.1275));
        return java.util.Collections.unmodifiableMap(positions);
    }

    /** Walks through the sequence from the current pose and collects one phase per step. */
    private final class Plan {

        private final SimulationContext context;
        private final String task;
        private final List<ScheduledChange> phases = new ArrayList<>();
        private final Map<String, Integer> occupancy = new HashMap<>();
        private Pose pose;
        private boolean suction;
        private String held;
        private Duration time = Duration.ZERO;

        Plan(SimulationContext context, String task) {
            this.context = context;
            this.task = task;
            ComponentState current = context.stateOf(getId);
            this.pose = new Pose(number(current, "x"), number(current, "y"), number(current, "z"), number(current, "r"));
            this.suction = Boolean.TRUE.equals(current.attributes().get("suction"));
            this.held = context.entitiesAt(getId).stream().findFirst().map(EntityView::id).orElse(null);
        }

        void move(Pose target, String label) {
            phases.add(ScheduledChange.at(time, StateChange.componentState(getId, state(pose, suction, task, "moving to " + label))));
            time = time.plus(timing.travel(pose, target));
            pose = target;
            phases.add(ScheduledChange.at(time, StateChange.componentState(getId, state(pose, suction, task, "at " + label))));
        }

        void home() {
            phases.add(ScheduledChange.at(time, StateChange.componentState(getId, state(pose, suction, task, "calibrating"))));
            time = time.plus(timing.homing());
            pose = HOME;
        }

        void suction(boolean on) {
            List<StateChange> changes = new ArrayList<>();
            suction = on;
            if (on && held == null && !context.isFaultActive(getId, MISSES_STONE)) {
                pickablePlace().ifPresent(place -> {
                    held = context.entitiesAt(place).get(0).id();
                    occupancy.merge(place, -1, Integer::sum);
                    changes.add(StateChange.move(held, getId));
                });
            } else if (!on && held != null) {
                String place = placeBelow();
                occupancy.merge(place, 1, Integer::sum);
                changes.add(StateChange.move(held, place));
                held = null;
            }
            changes.add(StateChange.componentState(getId, state(pose, suction, task, on ? "suction on" : "suction off")));
            phases.add(ScheduledChange.at(time, changes, List.of()));
        }

        ComponentExecutionResult finish() {
            phases.add(ScheduledChange.at(time, StateChange.componentState(getId, state(pose, suction, "", ""))));
            return ComponentExecutionResult.accepted(phases);
        }

        private Optional<String> pickablePlace() {
            return places.entrySet().stream()
                    .filter(place -> near(place.getValue(), true) && entitiesAt(place.getKey()) > 0)
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        private String placeBelow() {
            return places.entrySet().stream()
                    .filter(place -> near(place.getValue(), false) && entitiesAt(place.getKey()) == 0)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(fallbackPlace);
        }

        private int entitiesAt(String place) {
            return context.entitiesAt(place).size() + occupancy.getOrDefault(place, 0);
        }

        private boolean near(Pose place, boolean withHeight) {
            return Math.abs(place.x() - pose.x()) <= TOLERANCE_MM && Math.abs(place.y() - pose.y()) <= TOLERANCE_MM
                    && (!withHeight || Math.abs(place.z() - pose.z()) <= TOLERANCE_MM);
        }

        private static double number(ComponentState state, String key) {
            return ((Number) state.attributes().get(key)).doubleValue();
        }
    }
}
