package ch.hsg.simulation.adapters.dobot;

import ch.hsg.simulation.app.RealTimeService;
import ch.hsg.simulation.app.SimulationApplication;
import ch.hsg.simulation.model.SimulationStatus;
import ch.hsg.simulation.runtime.SimulationManager;
import ch.hsg.simulation.systems.dobot.DobotLayoutDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SimulationApplication.class, properties = "simulation.fischertechnik.mqtt.enabled=false")
@AutoConfigureMockMvc
class DobotControllerTest {

    private static final String PLAYBACK = "<root><row0><item_2>200</item_2><item_3>0</item_3><item_4>50</item_4>"
            + "<item_5>0</item_5></row0></root>";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SimulationManager manager;

    @Autowired
    private RealTimeService realTime;

    private UUID simulation;

    @BeforeEach
    void createAndStart() {
        simulation = manager.create(DobotLayoutDefinition.ID);
        manager.start(simulation);
        realTime.enable(simulation, 200);
    }

    @AfterEach
    void remove() {
        realTime.disable(simulation);
        if (manager.getSimulation(simulation).status() == SimulationStatus.RUNNING) {
            manager.stop(simulation);
        }
        manager.remove(simulation);
    }

    @Test
    void picksAStoneIntoTheFactory() throws Exception {
        manager.engine(simulation).placeEntity("stone", "stone", Map.of("color", "red"), "pos2");

        mvc.perform(get("/pick_up_and_transport?start=pos2&end=factory"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"end_position\":\"factory\","
                        + "\"message\":\"Moved from (272.3332, 63.7447, -73.8629, -134.4575) to factory\","
                        + "\"start_position\":[272.3332,63.7447,-73.8629,-134.4575],\"status\":\"success\"}"));

        assertThat(manager.getSimulation(simulation).entity("stone").orElseThrow().locationComponentId()).isEqualTo("factory");
    }

    @Test
    void movesBetweenCoordinates() throws Exception {
        mvc.perform(get("/move_to?start=pos1&end=200,0,50,0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Moved from (248.3179, 60.3962, -80.2133, -135.4775) to (200.0, 0.0, 50.0, 0.0)"))
                .andExpect(jsonPath("$.end_position[0]").value(200.0));

        assertThat(manager.getSimulation(simulation).component("dobot_1").orElseThrow().state().attributes())
                .containsEntry("x", 200.0);
    }

    @Test
    void reportsErrorsAsWerkzeugPages() throws Exception {
        mvc.perform(get("/move_to?start=pos1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string("<!doctype html>\n<html lang=en>\n<title>400 Bad Request</title>\n"
                        + "<h1>Bad Request</h1>\n<p>Both start and end parameters are required</p>\n"));
        mvc.perform(get("/move_to?start=foo&end=pos1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<p>Movement error: 400 Bad Request: Invalid position format: could not convert string to float: &#39;foo&#39;</p>")));
        mvc.perform(get("/pick_up_and_transport?start=1,2,3&end=pos1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<p>Invalid position format: Exactly 4 values (x,y,z,r) required</p>")));
    }

    @Test
    void moveToFactoryReachesStartThenFailsLikeTheLabCode() throws Exception {
        mvc.perform(get("/move_to?start=200,0,50,0&end=factory"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<p>Movement error: too many values to unpack (expected 4)</p>")));

        assertThat(manager.getSimulation(simulation).component("dobot_1").orElseThrow().state().attributes())
                .containsEntry("x", 200.0);
    }

    @Test
    void runXmlNeedsBothFileParts() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.playback", "application/xml",
                PLAYBACK.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/run_xml").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("No file part."));
        mvc.perform(multipart("/run_xml"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runXmlRunsThePlayback() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.playback", "application/xml",
                PLAYBACK.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile path = new MockMultipartFile("file_path", "path", "text/plain", new byte[0]);

        mvc.perform(multipart("/run_xml").file(file).file(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Dobot has finished executing commands."));
    }

    @Test
    void pythonFloatMatchesRepr() {
        assertThat(DobotController.pythonFloat(1)).isEqualTo("1.0");
        assertThat(DobotController.pythonFloat(-135.4775)).isEqualTo("-135.4775");
        assertThat(DobotController.pythonFloat(0.00001)).isEqualTo("1e-05");
        assertThat(DobotController.pythonFloat(1.5e16)).isEqualTo("1.5e+16");
        assertThat(DobotController.pythonFloat(123456789.0)).isEqualTo("123456789.0");
    }
}
