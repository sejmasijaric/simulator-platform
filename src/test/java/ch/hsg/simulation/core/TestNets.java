package ch.hsg.simulation.core;

import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.net.NetDefinition.Arc;
import ch.hsg.simulation.core.net.NetDefinition.HttpInterface;
import ch.hsg.simulation.core.net.NetDefinition.Place;
import ch.hsg.simulation.core.net.NetDefinition.Route;
import ch.hsg.simulation.core.net.NetDefinition.RouteKind;
import ch.hsg.simulation.core.net.NetDefinition.Sensor;
import ch.hsg.simulation.core.net.NetDefinition.SensorKind;
import ch.hsg.simulation.core.net.NetDefinition.Transition;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A small factory net, written in Java so the core can be tested without the YAML loader. It
 * mirrors the lab's burn process and sorting line: the gripper moves a workpiece between
 * positions named in the request, the oven burns whatever is on its platform when told to
 * (longer with a {@code time} parameter), the sorter reports a colour and sorts by it, and the
 * warehouse hands out a workpiece of the requested colour.
 */
public final class TestNets {

    public static final String LAYOUT = "test-factory";
    public static final Duration BURN = Duration.ofSeconds(12);
    public static final Duration MOVE = Duration.ofSeconds(5);
    public static final Duration SORT = Duration.ofSeconds(10);
    public static final Duration DETECT = Duration.ofSeconds(5);

    private TestNets() {
    }

    private static Transition transition(String name, List<Arc> in, List<Arc> out, Duration duration,
                                         Map<String, String> guard, List<String> inhibitors,
                                         Map<String, String> result, String module) {
        return new Transition(name, in, out, duration, null, null, guard, inhibitors, result, 0, module);
    }

    public static NetDefinition factory() {
        List<Place> places = List.of(
                new Place("dps", 1, 0, "gripper"),
                new Place("oven_platform", 1, 0, "oven"),
                new Place("oven_chamber", 1, 0, "oven"),
                new Place("pickup", 1, 0, "gripper"),
                new Place("scrap", NetDefinition.UNLIMITED, 0, null),
                new Place("oven_idle", 1, 1, "oven"),
                new Place("gripper_idle", 1, 1, "gripper"),
                new Place("cmd_burn", NetDefinition.UNLIMITED, 0, "oven"),
                new Place("oven_job", 1, 0, "oven"),
                new Place("cmd_move", NetDefinition.UNLIMITED, 0, "gripper"),
                new Place("sm_start", 1, 0, "sorter"),
                new Place("sm_idle", 1, 1, "sorter"),
                new Place("sink_1", 1, 0, "sorter"),
                new Place("sink_2", 1, 0, "sorter"),
                new Place("cmd_sort", NetDefinition.UNLIMITED, 0, "sorter"),
                new Place("cmd_detect", NetDefinition.UNLIMITED, 0, "sorter"),
                new Place("rack", 9, 0, "warehouse"),
                new Place("hbw_out", 1, 0, "warehouse"),
                new Place("cmd_unload", NetDefinition.UNLIMITED, 0, "warehouse"));

        List<Transition> transitions = List.of(
                transition("burn_start",
                        List.of(Arc.of("cmd_burn", "job"), Arc.of("oven_idle"), Arc.of("oven_platform", "wp")),
                        List.of(Arc.of("oven_chamber", "wp"), Arc.of("oven_job", "job")), Duration.ZERO,
                        null, null, null, "oven"),
                new Transition("burn_end",
                        List.of(Arc.of("oven_job", "job"), Arc.of("oven_chamber", "wp")),
                        List.of(Arc.of("oven_platform", "wp"), Arc.of("oven_idle")), BURN,
                        "time", Duration.ofSeconds(1), null, null, null, 0, "oven"),
                transition("move",
                        List.of(Arc.of("cmd_move"), Arc.of("gripper_idle"), Arc.of("${start}", "wp")),
                        List.of(Arc.of("${end}", "wp"), Arc.of("gripper_idle")), MOVE, null, null, null, "gripper"),
                transition("sort_red_by_color",
                        List.of(Arc.of("cmd_sort", "job"), Arc.of("sm_idle"), Arc.of("sm_start", "wp")),
                        List.of(Arc.of("sink_2", "wp"), Arc.of("sm_idle")), SORT,
                        Map.of("location", "none", "wp.color", "red"), null, Map.of("sink", "2"), "sorter"),
                transition("sort_predefined",
                        List.of(Arc.of("cmd_sort", "job"), Arc.of("sm_idle"), Arc.of("sm_start", "wp")),
                        List.of(Arc.of("${location}", "wp"), Arc.of("sm_idle")), SORT,
                        null, null, Map.of("sink", "null"), "sorter"),
                transition("detect",
                        List.of(Arc.of("cmd_detect"), Arc.of("sm_idle"), Arc.of("sm_start", "wp")),
                        List.of(Arc.of("sm_start", "wp"), Arc.of("sm_idle")), DETECT,
                        null, null, Map.of("color", "${wp.color}"), "sorter"),
                transition("detect_nothing",
                        List.of(Arc.of("cmd_detect"), Arc.of("sm_idle")),
                        List.of(Arc.of("sm_idle")), DETECT,
                        null, List.of("sm_start"), Map.of("color", "none"), "sorter"),
                transition("unload",
                        List.of(Arc.of("cmd_unload", "job"), Arc.of("rack", "wp")),
                        List.of(Arc.of("hbw_out", "wp")), Duration.ofSeconds(3),
                        Map.of("wp.color", "${color}"), null, null, "warehouse"));

        List<Sensor> sensors = List.of(
                Sensor.occupied("oven_light_barrier", "oven_platform"),
                new Sensor("dps_raw", SensorKind.OCCUPIED, "dps", null, Map.of("true", 0, "false", 1)),
                new Sensor("platform_color", SensorKind.ATTRIBUTE, "oven_platform", "color", null),
                new Sensor("burn_queue", SensorKind.COUNT, "cmd_burn", null, null));

        List<Route> routes = List.of(
                new Route("burn", RouteKind.COMMAND, "GET", "/ov/burn", List.of(), "cmd_burn", List.of("burn_end"),
                        Duration.ofSeconds(60), null, "[]"),
                new Route("move", RouteKind.COMMAND, "GET", "/vgr/move", List.of(), "cmd_move", List.of("move"),
                        Duration.ofSeconds(60), null, "[]"),
                new Route("light_barrier", RouteKind.QUERY, "GET", "/ov/status_of_light_barrier", List.of(),
                        null, null, null, "oven_light_barrier", "[{\"interrupted\": ${read}}]"));

        return new NetDefinition(LAYOUT, "Test factory", null, places, transitions, sensors,
                new HttpInterface(null, routes), List.of(), null, List.of());
    }
}
