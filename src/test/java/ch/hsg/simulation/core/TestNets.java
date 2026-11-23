package ch.hsg.simulation.core;

import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.net.NetDefinition.Arc;
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
 * A small oven-and-gripper net, written in Java so the core can be tested without the YAML
 * loader. It mirrors the lab's burn process: the gripper moves a workpiece between positions
 * named in the request, the oven burns whatever is on its platform when told to.
 */
public final class TestNets {

    public static final String LAYOUT = "test-oven";
    public static final Duration BURN = Duration.ofSeconds(12);
    public static final Duration MOVE = Duration.ofSeconds(5);

    private TestNets() {
    }

    public static NetDefinition oven() {
        List<Place> places = List.of(
                new Place("dps", 1, 0, "gripper"),
                new Place("oven_platform", 1, 0, "oven"),
                new Place("oven_chamber", 1, 0, "oven"),
                new Place("pickup", 1, 0, "gripper"),
                new Place("scrap", NetDefinition.UNLIMITED, 0, null),
                new Place("oven_idle", 1, 1, "oven"),
                new Place("gripper_idle", 1, 1, "gripper"),
                new Place("cmd_burn", NetDefinition.UNLIMITED, 0, "oven"),
                new Place("cmd_move", NetDefinition.UNLIMITED, 0, "gripper"));

        List<Transition> transitions = List.of(
                new Transition("burn_start",
                        List.of(Arc.of("cmd_burn"), Arc.of("oven_idle"), Arc.of("oven_platform", "wp")),
                        List.of(Arc.of("oven_chamber", "wp")), Duration.ZERO, 0, "oven"),
                new Transition("burn_end",
                        List.of(Arc.of("oven_chamber", "wp")),
                        List.of(Arc.of("oven_platform", "wp"), Arc.of("oven_idle")), BURN, 0, "oven"),
                new Transition("move",
                        List.of(Arc.of("cmd_move"), Arc.of("gripper_idle"), Arc.of("${start}", "wp")),
                        List.of(Arc.of("${end}", "wp"), Arc.of("gripper_idle")), MOVE, 0, "gripper"));

        List<Sensor> sensors = List.of(
                Sensor.occupied("oven_light_barrier", "oven_platform"),
                new Sensor("dps_raw", SensorKind.OCCUPIED, "dps", null, Map.of("true", 0, "false", 1)),
                new Sensor("platform_color", SensorKind.ATTRIBUTE, "oven_platform", "color", null),
                new Sensor("burn_queue", SensorKind.COUNT, "cmd_burn", null, null));

        List<Route> routes = List.of(
                new Route("burn", RouteKind.COMMAND, "GET", "/ov/burn", "cmd_burn", "burn_end",
                        Duration.ofSeconds(60), "{\"status\": \"success\"}", null),
                new Route("move", RouteKind.COMMAND, "GET", "/vgr/move", "cmd_move", "move",
                        Duration.ofSeconds(60), "{\"status\": \"success\"}", null),
                new Route("light_barrier", RouteKind.QUERY, "GET", "/ov/status_of_light_barrier", null, null,
                        null, "{\"interrupted\": ${oven_light_barrier}}", null));

        return new NetDefinition(LAYOUT, "Test oven", null, places, transitions, sensors, routes, List.of(), null);
    }
}
