package ch.hsg.simulation.core.net;

import ch.hsg.simulation.core.net.NetDefinition.Arc;
import ch.hsg.simulation.core.net.NetDefinition.HttpInterface;
import ch.hsg.simulation.core.net.NetDefinition.Place;
import ch.hsg.simulation.core.net.NetDefinition.Route;
import ch.hsg.simulation.core.net.NetDefinition.RouteKind;
import ch.hsg.simulation.core.net.NetDefinition.Sensor;
import ch.hsg.simulation.core.net.NetDefinition.SensorKind;
import ch.hsg.simulation.core.net.NetDefinition.Setting;
import ch.hsg.simulation.core.net.NetDefinition.Speed;
import ch.hsg.simulation.core.net.NetDefinition.Transition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings (e.g. motor speeds), speed-dependent durations and running-step sensors (ADR 0017). */
class SettingsAndRunningSensorsTest {

    private static final Setting SPEED = new Setting("m1", 512, -512, 512, null);

    private static Transition move(Speed speed) {
        return new Transition("move", List.of(Arc.of("a")), List.of(Arc.of("b")), Duration.ofSeconds(10),
                null, null, null, null, null, 0, null, speed);
    }

    private static NetDefinition net(List<Transition> transitions, List<Sensor> sensors, List<Route> routes,
                                     List<Setting> settings) {
        return new NetDefinition("n", null, null, List.of(Place.of("a"), Place.of("b")), transitions, sensors,
                new HttpInterface(null, routes), null, null, null, settings);
    }

    @Test
    void writesAreClampedToTheRange() {
        assertEquals(512, SPEED.clamp(9999));
        assertEquals(-512, SPEED.clamp(-9999));
        assertEquals(100, SPEED.clamp(100));
        assertThrows(IllegalArgumentException.class, () -> new Setting("s", 600, 0, 512, null));
    }

    @Test
    void theBaseDurationScalesWithTheSlowestMotor() {
        Transition transition = move(new Speed(List.of("m1", "m2"), 512));
        assertEquals(Duration.ofSeconds(10), transition.baseDurationAt(Map.of()).orElseThrow());
        assertEquals(Duration.ofSeconds(20), transition.baseDurationAt(Map.of("m1", 512, "m2", 256)).orElseThrow());
        assertEquals(Duration.ofSeconds(20), transition.baseDurationAt(Map.of("m1", -256, "m2", 512)).orElseThrow());
        assertTrue(transition.baseDurationAt(Map.of("m1", 0)).isEmpty());
        assertEquals(Duration.ofSeconds(10), move(null).baseDurationAt(Map.of("m1", 1)).orElseThrow());
    }

    @Test
    void namesAreChecked() {
        assertThrows(IllegalArgumentException.class, () ->
                net(List.of(move(new Speed(List.of("unknown"), 512))), List.of(), List.of(), List.of(SPEED)));
        assertThrows(IllegalArgumentException.class, () -> net(List.of(move(null)),
                List.of(new Sensor("task", SensorKind.RUNNING, null, null, null, Map.of("unknown", "x"))),
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> net(List.of(move(null)), List.of(),
                List.of(new Route("r", RouteKind.SETTING, "GET", "/r", List.of(), null, null, null, null, null,
                        null, List.of("unknown"))), List.of(SPEED)));
        assertThrows(IllegalArgumentException.class, () -> net(List.of(move(null)), List.of(), List.of(),
                List.of(SPEED, SPEED)));
        net(List.of(move(new Speed(List.of("m1"), 512))),
                List.of(new Sensor("task", SensorKind.RUNNING, null, null, null, Map.of("move", "moving"))),
                List.of(new Route("r", RouteKind.SETTING, "GET", "/r", List.of(), null, null, null, null, null,
                        Map.of("m${motor}", "${speed}"), List.of("m1"))), List.of(SPEED));
    }
}
