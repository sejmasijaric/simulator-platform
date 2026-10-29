package ch.hsg.simulation.adapters.rest;

import ch.hsg.simulation.api.SimulationDefinition;
import ch.hsg.simulation.app.SimulationApplication;
import ch.hsg.simulation.testlayout.TestLayoutDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {SimulationApplication.class, SimulationControllerTest.TestLayoutConfig.class})
@AutoConfigureMockMvc
class SimulationControllerTest {

    @TestConfiguration
    static class TestLayoutConfig {
        @Bean
        SimulationDefinition testLayout() {
            return new TestLayoutDefinition();
        }
    }

    private static final String TRANSPORT = """
            {"capability": "transport", "parameters": {"from": "IN", "to": "OUT"}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private String simulation;

    @BeforeEach
    void createAndStart() throws Exception {
        JsonNode created = body(postJson("/simulations", "{\"definitionId\": \"test-layout\"}")
                .andExpect(status().isCreated()));
        simulation = "/simulations/" + created.get("id").asText();
        mvc.perform(post(simulation + "/start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void listsDefinitions() throws Exception {
        JsonNode definitions = body(mvc.perform(get("/simulation-definitions")).andExpect(status().isOk()));
        assertThat(definitions.findValuesAsText("id")).contains("test-layout", "demo");
        JsonNode demo = definitions.findParents("id").stream()
                .filter(node -> node.get("id").asText().equals("demo")).findFirst().orElseThrow();
        assertThat(demo.at("/layoutView/positions/IN/x").asInt()).isPositive();
        assertThat(demo.at("/entityTemplates/0/attributes/color").asText()).isEqualTo("red");
    }

    @Test
    void executesTimedActionAndStepsThroughIt() throws Exception {
        postJson(simulation + "/components/T/actions", TRANSPORT)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duration").value("PT4S"));

        mvc.perform(post(simulation + "/step"))
                .andExpect(jsonPath("$.stepped").value(true))
                .andExpect(jsonPath("$.time").value("PT2S"));

        postJson(simulation + "/run-until", "{\"time\": \"PT10S\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time").value("PT10S"))
                .andExpect(jsonPath("$.entities[0].locationComponentId").value("OUT"));
    }

    @Test
    void rejectedActionReturns422() throws Exception {
        postJson(simulation + "/components/T/actions", "{\"capability\": \"fly\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void mapsCoreErrorsToHttpStatus() throws Exception {
        postJson(simulation + "/components/nope/actions", TRANSPORT).andExpect(status().isNotFound());
        postJson("/simulations", "{\"definitionId\": \"missing\"}").andExpect(status().isNotFound());
        postJson("/simulations", "{}").andExpect(status().isBadRequest());
        mvc.perform(delete(simulation)).andExpect(status().isConflict());
        mvc.perform(post(simulation + "/stop"));
        postJson(simulation + "/components/T/actions", TRANSPORT).andExpect(status().isConflict());
    }

    @Test
    void setsFaultsAndReturnsTrace() throws Exception {
        putJson(simulation + "/components/T/faults/ITEM_NOT_MOVED", "{\"active\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[?(@.id == 'T')].activeFaults[0]").value("ITEM_NOT_MOVED"));
        putJson(simulation + "/components/T/faults/MELTS", "{\"active\": true}").andExpect(status().isNotFound());

        JsonNode events = body(mvc.perform(get(simulation + "/events")).andExpect(status().isOk()));
        assertThat(events.findValuesAsText("type")).contains("SIMULATION_STARTED", "FAULT_ACTIVATED");

        JsonNode tail = body(mvc.perform(get(simulation + "/events").param("from", String.valueOf(events.size() - 1))));
        assertThat(tail.size()).isEqualTo(1);
    }

    @Test
    void realTimeDriverCompletesActions() throws Exception {
        putJson(simulation + "/real-time", "{\"speedFactor\": 50}")
                .andExpect(jsonPath("$.enabled").value(true));
        postJson(simulation + "/components/T/actions", TRANSPORT).andExpect(status().isAccepted());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        String location = "";
        while (System.nanoTime() < deadline && !location.equals("OUT")) {
            location = body(mvc.perform(get(simulation))).at("/entities/0/locationComponentId").asText();
            Thread.sleep(20);
        }
        assertThat(location).isEqualTo("OUT");

        mvc.perform(delete(simulation + "/real-time")).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void placesAndRemovesEntities() throws Exception {
        postJson(simulation + "/entities", """
                {"type": "workpiece", "attributes": {"color": "blue"}, "componentId": "OUT"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entities[?(@.id == 'workpiece-1')].locationComponentId").value("OUT"));
        postJson(simulation + "/entities", """
                {"type": "workpiece", "componentId": "OUT"}""").andExpect(status().isBadRequest());

        mvc.perform(delete(simulation + "/entities/workpiece-1")).andExpect(status().isOk());
        mvc.perform(delete(simulation + "/entities/workpiece-1")).andExpect(status().isNotFound());
    }

    @Test
    void awaitBlocksUntilTheActionCompletes() throws Exception {
        putJson(simulation + "/real-time", "{\"speedFactor\": 50}");

        postJson(simulation + "/components/T/actions?await=true", """
                {"capability": "transport", "parameters": {"from": "IN", "to": "OUT"}, "origin": "application"}""")
                .andExpect(status().isOk());

        mvc.perform(get(simulation)).andExpect(jsonPath("$.entities[0].locationComponentId").value("OUT"));
        JsonNode events = body(mvc.perform(get(simulation + "/events")));
        assertThat(events.findParents("type").stream()
                .filter(e -> e.get("type").asText().equals("ACTION_ACCEPTED"))
                .map(e -> e.get("origin").asText())).containsExactly("application");
    }

    @Test
    void awaitTimesOutWhenTimeDoesNotAdvance() throws Exception {
        postJson(simulation + "/components/T/actions?await=true&timeout=PT0.2S", TRANSPORT)
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    void resetAndRemove() throws Exception {
        postJson(simulation + "/components/T/actions", TRANSPORT);
        mvc.perform(post(simulation + "/reset"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.time").value("PT0S"))
                .andExpect(jsonPath("$.entities[0].locationComponentId").value("IN"));

        mvc.perform(delete(simulation)).andExpect(status().isNoContent());
        mvc.perform(get(simulation)).andExpect(status().isNotFound());
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putJson(String path, String body) throws Exception {
        return mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
