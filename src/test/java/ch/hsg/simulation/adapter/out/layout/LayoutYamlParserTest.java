package ch.hsg.simulation.adapter.out.layout;

import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.net.NetDefinition.RouteKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutYamlParserTest {

    private final LayoutYamlParser parser = new LayoutYamlParser();

    private static String bundled(String name) throws IOException {
        try (InputStream in = LayoutYamlParserTest.class.getResourceAsStream("/layouts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theFischertechnikLayoutLoads() throws IOException {
        NetDefinition net = parser.parse(bundled("fischertechnik.yaml"), "fischertechnik.yaml");

        assertEquals("fischertechnik", net.id());
        assertEquals(Duration.ofSeconds(20), net.transition("vgr_pick_up").orElseThrow().duration());
        assertEquals(Duration.ofMillis(2400), net.transition("ov_burn").orElseThrow().durationPerUnit());
        assertEquals(List.of("vgr_drop", "vgr_move_empty"),
                net.route("vgr_pick_up_and_transport").orElseThrow().returnsAfter());
        assertEquals(RouteKind.QUERY, net.route("ov_status_of_light_barrier").orElseThrow().kind());
        assertEquals(Map.of("true", 0, "false", 1), net.sensor("ov_i5").orElseThrow().values());
        assertEquals(404, net.route("ov_burn").orElseThrow().parameters().get(0).missing().status());
        assertEquals(5, net.events().size());
        assertEquals(3, net.tokenTemplates().size());
        assertTrue(net.image().positions().containsKey("oven"));
    }

    @Test
    void shortAndIsoDurationsAreUnderstood() {
        assertEquals(Duration.ofMillis(500), LayoutYamlParser.duration("500ms", "d"));
        assertEquals(Duration.ofMillis(2400), LayoutYamlParser.duration("2.4s", "d"));
        assertEquals(Duration.ofMinutes(30), LayoutYamlParser.duration("30m", "d"));
        assertEquals(Duration.ofSeconds(45), LayoutYamlParser.duration("PT45S", "d"));
        assertEquals(Duration.ofSeconds(3), LayoutYamlParser.duration(3, "d"));
        assertThrows(IllegalArgumentException.class, () -> LayoutYamlParser.duration("soon", "d"));
    }

    @Test
    void aMinimalLayoutNeedsOnlyIdAndPlaces() {
        NetDefinition net = parser.parse("""
                id: tiny
                places:
                  a: {initial: 1}
                  b:
                transitions:
                  move: {in: [a], out: [b], duration: 1s}
                """, "tiny.yaml");
        assertEquals(2, net.places().size());
        assertEquals(1, net.transitions().size());
    }

    @Test
    void typosAreReportedWithTheFileName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                id: tiny
                places:
                  a: {capacty: 1}
                """, "tiny.yaml"));
        assertTrue(error.getMessage().contains("tiny.yaml"), error.getMessage());
        assertTrue(error.getMessage().contains("capacty"), error.getMessage());
    }

    @Test
    void anUnknownPlaceInTheNetIsReported() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                id: tiny
                places:
                  a:
                transitions:
                  move: {in: [a], out: [nowhere]}
                """, "tiny.yaml"));
        assertTrue(error.getMessage().contains("unknown place nowhere"), error.getMessage());
    }
}
