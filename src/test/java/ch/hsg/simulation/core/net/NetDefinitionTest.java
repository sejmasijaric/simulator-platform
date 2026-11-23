package ch.hsg.simulation.core.net;

import ch.hsg.simulation.core.TestNets;
import ch.hsg.simulation.core.net.NetDefinition.Arc;
import ch.hsg.simulation.core.net.NetDefinition.Place;
import ch.hsg.simulation.core.net.NetDefinition.Route;
import ch.hsg.simulation.core.net.NetDefinition.RouteKind;
import ch.hsg.simulation.core.net.NetDefinition.Sensor;
import ch.hsg.simulation.core.net.NetDefinition.Transition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetDefinitionTest {

    private static NetDefinition net(List<Place> places, List<Transition> transitions, List<Sensor> sensors,
                                     List<Route> routes) {
        return new NetDefinition("n", null, null, places, transitions, sensors, routes, List.of(), null);
    }

    @Test
    void theTestNetIsValid() {
        NetDefinition net = TestNets.oven();
        assertEquals(3, net.transitions().size());
        assertTrue(net.route("burn").isPresent());
    }

    @Test
    void anArcToAnUnknownPlaceIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> net(
                List.of(Place.of("a")),
                List.of(Transition.of("t", List.of(Arc.of("a")), List.of(Arc.of("b")), Duration.ZERO)),
                List.of(), List.of()));
        assertTrue(error.getMessage().contains("unknown place b"), error.getMessage());
    }

    @Test
    void aTemplatePlaceIsCheckedOnlyWhenItFires() {
        net(List.of(Place.of("cmd")),
                List.of(Transition.of("t", List.of(Arc.of("cmd"), Arc.of("${start}", "wp")),
                        List.of(Arc.of("${end}", "wp")), Duration.ZERO)),
                List.of(), List.of());
    }

    @Test
    void anOutputVariableMustBeBoundByAnInput() {
        assertThrows(IllegalArgumentException.class, () -> Transition.of("t",
                List.of(Arc.of("a")), List.of(Arc.of("b", "wp")), Duration.ZERO));
    }

    @Test
    void routesAndSensorsMustNameExistingParts() {
        assertThrows(IllegalArgumentException.class, () -> net(List.of(Place.of("a")), List.of(),
                List.of(Sensor.occupied("s", "missing")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> net(List.of(Place.of("a")), List.of(), List.of(),
                List.of(new Route("r", RouteKind.COMMAND, "GET", "/r", "a", "missing", null, null, null))));
    }

    @Test
    void duplicatePlacesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> net(List.of(Place.of("a"), Place.of("a")),
                List.of(), List.of(), List.of()));
    }

    @Test
    void initialTokensMustFitTheCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new Place("a", 1, 2, null));
    }

    @Test
    void templatesFillPlaceholders() {
        assertEquals("{\"interrupted\": true}",
                Templates.render("{\"interrupted\": ${lb}}", Map.<String, Object>of("lb", true)::get));
        assertThrows(IllegalArgumentException.class, () -> Templates.render("${missing}", key -> null));
        assertTrue(Templates.isTemplate("${start}"));
    }
}
