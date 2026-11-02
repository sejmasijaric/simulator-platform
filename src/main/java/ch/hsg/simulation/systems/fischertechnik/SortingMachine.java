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
 * Sorting machine. {@code detect_color} runs the belt from {@code startSlot} to the colour sensor
 * ({@code colorSlot}) and stores the colour in {@code last_color}. {@code sort} detects the colour
 * the same way and ejects the workpiece into a sink: the predefined one, or by colour when the
 * location is {@code none} (white → sink 1, red → 2, blue → 3); the chosen sink goes to
 * {@code last_sink}.
 *
 * <p>As on the physical machine, a workpiece already at the colour sensor reads as {@code none}, so
 * {@code sort} with location {@code none} after {@code detect_color} fails. Fault COLOR_MISREAD
 * reports the next colour (white → red → blue → white).
 */
public record SortingMachine(String getId, String startSlot, String colorSlot, List<String> sinks,
                             Duration detectDuration, Duration sinkDuration) implements Component {

    public static final String DETECT_COLOR = "detect_color";
    public static final String SORT = "sort";
    public static final String COLOR_MISREAD = "COLOR_MISREAD";
    public static final String LAST_COLOR = "last_color";
    public static final String LAST_SINK = "last_sink";
    static final List<String> COLORS = List.of("white", "red", "blue");
    static final String NONE = "none";
    static final Duration NFC_DURATION = Duration.ofSeconds(1);
    private static final Set<String> STARTS = Set.of("initial", "sm_cb");
    private static final Set<String> LOCATIONS = Set.of("sink_1", "sink_2", "sink_3", "corner", NONE);

    public SortingMachine {
        sinks = List.copyOf(sinks);
    }

    @Override
    public String getType() {
        return "sorting-machine";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(
                Capability.of(DETECT_COLOR),
                Capability.of(SORT, Map.of("start", ParameterType.STRING,
                        "predefined_ejection_location", ParameterType.STRING, "use_nfc", ParameterType.BOOLEAN)));
    }

    @Override
    public Set<FaultMode> getFaultModes() {
        return Set.of(new FaultMode(COLOR_MISREAD, "Reports the wrong colour, so the workpiece goes to the wrong sink"));
    }

    @Override
    public ComponentState initialState() {
        return MachineStatus.initial().with(Map.of(LAST_COLOR, "", LAST_SINK, ""));
    }

    @Override
    public ComponentExecutionResult execute(ComponentAction action, SimulationContext context) {
        if (context.isBusy(getId)) {
            return ComponentExecutionResult.rejected(getId + " is busy");
        }
        return DETECT_COLOR.equals(action.capability()) ? detectColor(context) : sort(action, context);
    }

    private ComponentExecutionResult detectColor(SimulationContext context) {
        Detection detection = detect(context);
        ComponentState started = MachineStatus.working(context.stateOf(getId), "", "detecting the workpiece color");
        List<StateChange> detected = new ArrayList<>(detection.moves());
        detected.add(StateChange.componentState(getId, MachineStatus.idle(started).with(LAST_COLOR, detection.color())));
        return ComponentExecutionResult.accepted(
                ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, started)),
                ScheduledChange.at(detection.duration(), detected, List.of()));
    }

    private ComponentExecutionResult sort(ComponentAction action, SimulationContext context) {
        String start = action.stringParameter("start");
        String location = action.stringParameter("predefined_ejection_location");
        boolean useNfc = (Boolean) action.parameters().get("use_nfc");
        if (!LOCATIONS.contains(location)) {
            return ComponentExecutionResult.rejected("Sink " + location + " is not a valid ejection position");
        }
        if (!STARTS.contains(start)) {
            return ComponentExecutionResult.rejected("Start " + start + " is not a valid start position");
        }
        if (!"initial".equals(start) || "corner".equals(location)) {
            return ComponentExecutionResult.rejected("start=" + start + " and location=" + location + " are not supported by the simulator yet");
        }
        Detection detection = detect(context);
        if (NONE.equals(location) && NONE.equals(detection.color())) {
            return ComponentExecutionResult.rejected("'none' is not in list");
        }
        boolean byColor = NONE.equals(location);
        int sinkNumber = byColor ? COLORS.indexOf(detection.color()) + 1 : Integer.parseInt(location.substring("sink_".length()));
        String sink = sinks.get(sinkNumber - 1);

        ComponentState started = MachineStatus.working(context.stateOf(getId),
                "sorting the workpiece which is located at " + start + (useNfc ? " using" : " without using")
                        + " the nfc reader " + (byColor ? "into a sink based on the detected color" : "into the predefined ejection location " + location),
                "detecting the workpiece color");
        Duration detected = detection.duration().plus(useNfc ? NFC_DURATION : Duration.ZERO);
        List<StateChange> atColor = new ArrayList<>(detection.moves());
        atColor.add(StateChange.componentState(getId, started.with(Map.of(
                MachineStatus.SUB_TASK, "transporting the workpiece to the sink " + sinkNumber, LAST_COLOR, detection.color()))));
        List<StateChange> ejected = new ArrayList<>();
        detection.workpiece().ifPresent(entity -> ejected.add(StateChange.move(entity.id(), sink)));
        ejected.add(StateChange.componentState(getId, MachineStatus.idle(started).with(Map.of(
                LAST_COLOR, detection.color(), LAST_SINK, byColor ? sinkNumber : ""))));
        return ComponentExecutionResult.accepted(
                ScheduledChange.at(Duration.ZERO, StateChange.componentState(getId, started)),
                ScheduledChange.at(detected, atColor, List.of()),
                ScheduledChange.at(detected.plus(sinkDuration), ejected, List.of()));
    }

    /** What the colour sensor sees: the workpiece at the belt start passes it, anything else reads as none. */
    private Detection detect(SimulationContext context) {
        Optional<EntityView> atStart = context.entitiesAt(startSlot).stream().findFirst();
        if (atStart.isPresent()) {
            String color = String.valueOf(atStart.get().attributes().get("color"));
            if (COLORS.contains(color) && context.isFaultActive(getId, COLOR_MISREAD)) {
                color = COLORS.get((COLORS.indexOf(color) + 1) % COLORS.size());
            }
            return new Detection(COLORS.contains(color) ? color : NONE, atStart,
                    List.of(StateChange.move(atStart.get().id(), colorSlot)), detectDuration);
        }
        Optional<EntityView> atSensor = context.entitiesAt(colorSlot).stream().findFirst();
        return new Detection(NONE, atSensor, List.of(), atSensor.isPresent() ? Duration.ofMillis(500) : detectDuration);
    }

    private record Detection(String color, Optional<EntityView> workpiece, List<StateChange> moves, Duration duration) {
    }
}
