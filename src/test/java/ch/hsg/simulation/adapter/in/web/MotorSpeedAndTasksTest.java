package ch.hsg.simulation.adapter.in.web;

import ch.hsg.simulation.adapter.in.web.LayoutHttpApi.Request;
import ch.hsg.simulation.adapter.in.web.LayoutHttpApi.Response;
import ch.hsg.simulation.adapter.out.layout.LayoutYamlParser;
import ch.hsg.simulation.core.event.EventType;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Motor speeds (settings), the running-step texts of the MQTT messages and the out-of-service
 * warehouse of the Fischertechnik layout, as smart-factory-python implements them (ADR 0016, 0017).
 * Time is moved by hand: the adapter's switch to real time is undone after each call.
 */
class MotorSpeedAndTasksTest {

    private static final List<String> BURN_ENDS = List.of("ov_burn", "ov_burn_empty");

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
        Response response = api.handle(new Request("GET", path, query, "http://localhost:8080" + path)).orElseThrow();
        manager.stopRealTime(simulation);
        return response;
    }

    private String lastDuration(String transition) {
        return manager.events(simulation, 0).stream()
                .filter(event -> event.type() == EventType.TRANSITION_STARTED && transition.equals(event.subject()))
                .map(event -> String.valueOf(event.data().get("duration")))
                .reduce((first, second) -> second).orElse("not started");
    }

    private Object sensor(String name) {
        return manager.readSensors(simulation).get(name);
    }

    @Test
    void motorSpeedsCanBeReadSetClampedAndReset() {
        assertTrue(get("/ov/get_motor_speed", "machine", "ov_1", "motor", "1").body()
                .contains("\"attributes\": [{\"motor_speed\": 512}]"));

        Response set = get("/ov/set_motor_speed", "machine", "ov_1", "motor", "1", "speed", "256");
        assertEquals(200, set.status());
        assertTrue(set.body().contains("\"attributes\": []"));
        assertTrue(get("/ov/get_motor_speed", "machine", "ov_1", "motor", "1").body().contains("\"motor_speed\": 256"));

        get("/vgr/set_motor_speed", "machine", "vgr_1", "motor", "2", "speed", "9999");
        assertEquals(512, manager.snapshot(simulation).settings().get("vgr_m2_speed"));
        get("/vgr/set_motor_speed", "machine", "vgr_1", "motor", "2", "speed", "-9999");
        assertEquals(-512, manager.snapshot(simulation).settings().get("vgr_m2_speed"));

        assertEquals(200, get("/vgr/reset_all_motor_speeds", "machine", "vgr_1").status());
        assertEquals(512, manager.snapshot(simulation).settings().get("vgr_m2_speed"));
    }

    @Test
    void motorSpeedErrorsAreTheControllers() {
        Response unknownMotor = get("/ov/set_motor_speed", "machine", "ov_1", "motor", "2", "speed", "100");
        assertEquals(400, unknownMotor.status());
        assertTrue(unknownMotor.body().contains("<class 'ValueError'>:Trying to access non-existing motor 2"));
        assertEquals(404, get("/ov/set_motor_speed", "machine", "ov_1", "motor", "1").status());
        assertEquals(500, get("/ov/set_motor_speed", "machine", "ov_1", "motor", "1", "speed", "fast").status());
        assertEquals(400, get("/mm/get_motor_speed", "machine", "mm_1", "motor", "4").status());
        assertEquals(200, get("/wt/get_motor_speed", "machine", "wt_1", "motor", "2").status());
    }

    @Test
    void aSlowerMotorStretchesOnlyTheMotorPartOfAStep() {
        manager.setSetting(simulation, "ov_m1_speed", 256, "control");
        manager.placeToken(simulation, "oven", null, Map.of("color", "red"));
        manager.command(simulation, "cmd_ov_burn", Map.of("machine", "ov_1", "time", "2"), BURN_ENDS, "application");

        assertEquals("PT10S", lastDuration("ov_drive_in"));      // 5 s of driving at half speed
        manager.step(simulation);
        assertEquals("PT14.8S", lastDuration("ov_burn"));        // 5 s x 2, plus 2 x 2.4 s of blinking
    }

    @Test
    void aStoppedMotorHoldsTheStepUntilItRunsAgain() {
        manager.setSetting(simulation, "ov_m1_speed", 0, "control");
        manager.placeToken(simulation, "oven", null, Map.of("color", "red"));
        manager.command(simulation, "cmd_ov_burn", Map.of("machine", "ov_1"), BURN_ENDS, "application");
        assertTrue(manager.snapshot(simulation).running().isEmpty());

        manager.setSetting(simulation, "ov_m1_speed", -512, "control"); // reversed: same speed
        assertEquals("PT5S", lastDuration("ov_drive_in"));
    }

    @Test
    void theMessagesShowTheRunningTaskLikeTheControllers() {
        manager.placeToken(simulation, "oven", null, Map.of("color", "red"));
        manager.command(simulation, "cmd_ov_burn", Map.of("machine", "ov_1", "time", "2"), BURN_ENDS, "application");

        assertEquals("burning the workpiece for 2 seconds", sensor("ov_task"));
        assertEquals("transporting the workpiece to the inside of the oven", sensor("ov_sub_task"));
        assertEquals("", sensor("vgr_task"));
        manager.step(simulation);
        assertEquals("burning the workpiece for 2 seconds", sensor("ov_sub_task"));
        manager.step(simulation);
        assertEquals("", sensor("ov_task"));
    }

    @Test
    void theWarehouseIsOutOfServiceUntilATokenSaysOtherwise() {
        manager.placeToken(simulation, "high_bay_warehouse_rack", null, Map.of("color", "blue"));
        manager.command(simulation, "cmd_hbw_unload", Map.of("color", "blue"), List.of("hbw_unload"), "application");
        assertTrue(manager.snapshot(simulation).running().isEmpty());

        manager.placeToken(simulation, "hbw_in_service", null, Map.of());
        assertEquals("hbw_unload", manager.snapshot(simulation).running().get(0).transition());
    }

    @Test
    void aResetPutsTheSpeedsBack() {
        manager.setSetting(simulation, "sm_m1_speed", 100, "control");
        manager.reset(simulation);
        assertEquals(512, manager.snapshot(simulation).settings().get("sm_m1_speed"));
    }
}
