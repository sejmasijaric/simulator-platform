package ch.hsg.simulation.adapter.in.web;

import ch.hsg.simulation.adapter.in.web.LayoutHttpApi.Request;
import ch.hsg.simulation.adapter.in.web.LayoutHttpApi.Response;
import ch.hsg.simulation.adapter.out.layout.LayoutYamlParser;
import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.runtime.SimulationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Fischertechnik layout through its own HTTP API, as an unchanged lab application would call it. */
class LayoutHttpApiTest {

    private SimulationManager manager;
    private LayoutHttpApi api;
    private UUID simulation;

    @BeforeEach
    void setUp() throws IOException {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/layouts/fischertechnik.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        NetDefinition factory = new LayoutYamlParser().parse(yaml, "fischertechnik.yaml");
        manager = new SimulationManager(() -> List.of(factory));
        api = new LayoutHttpApi(manager, Clock.systemDefaultZone());
        simulation = manager.create("fischertechnik");
        manager.start(simulation);
        manager.setSpeed(simulation, 500); // the 45 s gripper move takes about 0.1 s
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private Response get(String path, String... parameters) {
        Map<String, String> query = new LinkedHashMap<>();
        for (int index = 0; index < parameters.length; index += 2) {
            query.put(parameters[index], parameters[index + 1]);
        }
        return api.handle(new Request("GET", path, query, "http://localhost:8080" + path)).orElseThrow();
    }

    @Test
    void theLabProductionProcessRunsUnchanged() {
        manager.placeToken(simulation, "delivery_pick_up_station", null, Map.of("color", "blue"));

        assertEquals(200, get("/vgr/pick_up_and_transport", "machine", "vgr_1",
                "start", "delivery_pick_up_station", "end", "oven").status());
        assertEquals(200, get("/ov/burn", "machine", "ov_1").status());
        assertEquals(200, get("/wt/pick_up_and_transport", "machine", "wt_1",
                "start", "oven", "end", "milling_machine").status());
        assertEquals(200, get("/mm/mill", "machine", "mm_1", "time", "10", "start", "initial", "end", "ejection").status());
        Response color = get("/sm/detect_color", "machine", "sm_1");
        Response sort = get("/sm/sort", "machine", "sm_1", "start", "initial", "predefined_ejection_location", "sink_1");

        assertTrue(color.body().startsWith("{\"attributes\": [{\"color\": \"blue\"}]"), color.body());
        assertTrue(sort.body().contains("\"sink\": null"), sort.body());
        assertTrue(sort.body().contains("\"link\": \"http://localhost:8080/sm/sort\""), sort.body());
        assertEquals(1, manager.snapshot(simulation).tokensAt("sink_1").size());
    }

    @Test
    void sortingByColourAnswersWithTheSink() {
        manager.placeToken(simulation, "sorting_machine_start", null, Map.of("color", "red"));
        Response sort = get("/sm/sort", "machine", "sm_1", "start", "initial", "predefined_ejection_location", "none");
        assertTrue(sort.body().startsWith("{\"attributes\": [{\"sink\": 2}]"), sort.body());
    }

    @Test
    void queriesReadSensorsWithoutFiring() {
        manager.placeToken(simulation, "oven", null, Map.of("color", "white"));
        assertTrue(get("/ov/status_of_light_barrier", "machine", "ov_1", "lb", "5").body()
                .startsWith("{\"attributes\": [{\"interrupted\": true}]"));
        assertTrue(get("/ov/state_of_machine", "machine", "ov_1").body()
                .startsWith("{\"attributes\": [{\"state\": \"ready\"}]"));
    }

    @Test
    void parametersAreCheckedLikeThePhysicalApi() {
        assertEquals(new Response(404, "{\"error\": \"404 Not Found: No Machine is passed\"}"), get("/ov/burn"));
        assertEquals(404, get("/ov/burn", "machine", "ov_2").status());
        Response position = get("/vgr/pick_up_and_transport", "machine", "vgr_1", "start", "moon", "end", "oven");
        assertEquals(400, position.status());
        assertTrue(position.body().contains("Position moon does not exist"), position.body());
        assertEquals(500, get("/ov/burn", "machine", "ov_1", "time", "abc").status());
        assertEquals(400, get("/sm/status_of_light_barrier", "machine", "sm_1", "lb", "9").status());
    }

    @Test
    void withoutARunningSimulationTheApiSaysSo() {
        manager.stop(simulation);
        assertEquals(503, get("/ov/burn", "machine", "ov_1").status());
    }

    @Test
    void unknownEndpointsAreLeftToOthers() {
        assertFalse(api.handles("GET", "/simulations"));
        assertTrue(api.handle(new Request("GET", "/index.html", Map.of(), "")).isEmpty());
    }
}
