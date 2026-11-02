package ch.hsg.simulation.adapters.fischertechnik;

import ch.hsg.simulation.app.RealTimeService;
import ch.hsg.simulation.app.SimulationApplication;
import ch.hsg.simulation.model.SimulationStatus;
import ch.hsg.simulation.runtime.SimulationManager;
import ch.hsg.simulation.systems.fischertechnik.FischertechnikLayoutDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SimulationApplication.class)
@AutoConfigureMockMvc
class FischertechnikControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SimulationManager manager;

    @Autowired
    private RealTimeService realTime;

    private UUID simulation;

    @BeforeEach
    void createAndStart() {
        simulation = manager.create(FischertechnikLayoutDefinition.ID);
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
    void runsTheLabProcess() throws Exception {
        manager.engine(simulation).placeEntity("wp", "workpiece", Map.of("color", "white"), "delivery_pick_up_station");

        mvc.perform(get("/vgr/pick_up_and_transport?machine=vgr_1&start=delivery_pick_up_station&end=oven"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value("http://localhost/vgr/pick_up_and_transport"))
                .andExpect(jsonPath("$.attributes").isEmpty())
                .andExpect(jsonPath("$.start_time").value(matchesPattern("\\d{2}-[A-Z][a-z]{2}-\\d{4} \\(\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\)")));
        mvc.perform(get("/ov/status_of_light_barrier?machine=ov_1&lb=5"))
                .andExpect(jsonPath("$.attributes[0].interrupted").value(true));
        mvc.perform(get("/ov/burn?machine=ov_1")).andExpect(status().isOk());
        mvc.perform(get("/wt/pick_up_and_transport?machine=wt_1&start=oven&end=milling_machine")).andExpect(status().isOk());
        mvc.perform(get("/mm/mill?machine=mm_1&time=10&start=initial&end=ejection")).andExpect(status().isOk());
        mvc.perform(get("/sm/detect_color?machine=sm_1"))
                .andExpect(jsonPath("$.attributes[0].color").value("white"));
        mvc.perform(get("/sm/sort?machine=sm_1&start=initial&predefined_ejection_location=sink_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes[0].sink").value(nullValue()));

        assertThat(manager.getSimulation(simulation).entity("wp").orElseThrow().locationComponentId()).isEqualTo("sink_1");
        mvc.perform(get("/sm/status_of_light_barrier?machine=sm_1&lb=6"))
                .andExpect(jsonPath("$.attributes[0].interrupted").value(true));
        mvc.perform(get("/sm/state_of_machine?machine=sm_1"))
                .andExpect(jsonPath("$.attributes[0].state").value("ready"));
    }

    @Test
    void reportsErrorsLikeTheFactory() throws Exception {
        mvc.perform(get("/vgr/pick_up_and_transport?start=delivery_pick_up_station&end=oven"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("404 Not Found: No Machine is passed"));
        mvc.perform(get("/vgr/pick_up_and_transport?machine=vgr_2&start=delivery_pick_up_station&end=oven"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/vgr/pick_up_and_transport?machine=vgr_1&start=foo&end=oven"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "400 Bad Request: The machine controller of the addressed machine encountered an error: <class 'ValueError'>:Position foo does not exist"));
        mvc.perform(get("/ov/burn?machine=ov_1&time=abc")).andExpect(status().isInternalServerError());
        mvc.perform(get("/mm/status_of_light_barrier?machine=mm_1&lb=9")).andExpect(status().isBadRequest());
    }

    @Test
    void needsARunningSimulation() throws Exception {
        manager.stop(simulation);

        mvc.perform(get("/sm/detect_color?machine=sm_1"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void formatsProcessTimeLikePython() {
        assertThat(FischertechnikController.pythonTimedelta(Duration.ofMillis(8004).plusNanos(512_000))).isEqualTo("0:00:08.004512");
        assertThat(FischertechnikController.pythonTimedelta(Duration.ofMinutes(61))).isEqualTo("1:01:00");
    }
}
