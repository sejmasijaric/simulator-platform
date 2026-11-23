package ch.hsg.simulation.core.runtime;

import ch.hsg.simulation.core.NotFoundException;
import ch.hsg.simulation.core.SimulationException;
import ch.hsg.simulation.core.TestNets;
import ch.hsg.simulation.core.event.EventType;
import ch.hsg.simulation.core.event.SimulationEvent;
import ch.hsg.simulation.core.fault.NetFault;
import ch.hsg.simulation.core.fault.SensorFault;
import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.net.NetDefinition.Arc;
import ch.hsg.simulation.core.net.NetDefinition.Place;
import ch.hsg.simulation.core.net.NetDefinition.Transition;
import ch.hsg.simulation.core.net.Token;
import ch.hsg.simulation.core.port.CommandHandle;
import ch.hsg.simulation.core.port.SimulationStatus;
import ch.hsg.simulation.core.port.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationEngineTest {

    private SimulationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SimulationEngine(UUID.randomUUID(), TestNets.factory());
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private CommandHandle burn() {
        return engine.command("cmd_burn", Map.of(), "burn_end", null);
    }

    private CommandHandle move(String start, String end) {
        return engine.command("cmd_move", Map.of("start", start, "end", end), "move", null);
    }

    private List<String> idsAt(String place) {
        return engine.snapshot().tokensAt(place).stream().map(Token::id).toList();
    }

    private static Duration seconds(long value) {
        return Duration.ofSeconds(value);
    }

    // ---- commands and time ----

    @Test
    void burnReturnsAfterTheBurnDuration() {
        engine.placeToken("oven_platform", "wp1", Map.of("color", "red"));

        CommandHandle handle = burn();
        engine.runUntil(seconds(11));
        assertFalse(handle.completion().isDone());

        engine.runUntil(seconds(12));
        assertTrue(handle.completion().isDone());
        assertEquals(seconds(12), handle.completion().join().time());
        assertEquals(List.of("wp1"), idsAt("oven_platform"));
        assertEquals(1, engine.snapshot().tokensAt("oven_idle").size());
    }

    @Test
    void workpieceIsInsideTheTransitionWhileBurning() {
        engine.placeToken("oven_platform", "wp1", Map.of());
        burn();
        engine.runUntil(seconds(5));

        Snapshot snapshot = engine.snapshot();
        assertEquals(List.of(), snapshot.tokensAt("oven_platform"));
        assertEquals(1, snapshot.running().size());
        assertEquals("burn_end", snapshot.running().get(0).transition());
        assertEquals(seconds(12), snapshot.running().get(0).ends());
        assertEquals(false, engine.readSensors().get("oven_light_barrier"));
    }

    @Test
    void aSecondRequestWaitsInTheCommandPlaceUntilTheOvenIsReady() {
        engine.placeToken("oven_platform", "wp1", Map.of());
        CommandHandle first = burn();
        CommandHandle second = burn();

        engine.runUntil(seconds(1));
        assertEquals(1, engine.readSensors().get("burn_queue"));

        engine.runUntil(seconds(12));
        assertTrue(first.completion().isDone());
        assertFalse(second.completion().isDone());

        engine.runUntil(seconds(24));
        assertEquals(seconds(24), second.completion().join().time());
    }

    @Test
    void theOvenDoesNothingWithoutARequest() {
        engine.placeToken("oven_platform", "wp1", Map.of());
        engine.runUntil(seconds(60));
        assertEquals(List.of("wp1"), idsAt("oven_platform"));
        assertTrue(engine.snapshot().running().isEmpty());
    }

    @Test
    void moveTakesItsPlacesFromTheRequestParameters() {
        engine.placeToken("dps", "wp1", Map.of());

        CommandHandle handle = move("dps", "oven_platform");
        engine.runUntil(seconds(5));

        assertTrue(handle.completion().isDone());
        assertEquals(List.of("wp1"), idsAt("oven_platform"));
        assertEquals(List.of(), idsAt("dps"));
    }

    @Test
    void theBurnProcessRunsEndToEnd() {
        engine.placeToken("dps", "wp1", Map.of());

        move("dps", "oven_platform");
        engine.runUntil(seconds(5));
        burn();
        engine.runUntil(seconds(17));
        CommandHandle last = move("oven_platform", "pickup");
        engine.runUntil(seconds(22));

        assertEquals(seconds(22), last.completion().join().time());
        assertEquals(List.of("wp1"), idsAt("pickup"));
    }

    @Test
    void aMoveToAFullPlaceWaitsUntilItIsFree() {
        engine.placeToken("dps", "wp1", Map.of());
        engine.placeToken("oven_platform", "wp2", Map.of());

        CommandHandle handle = move("dps", "oven_platform");
        engine.runUntil(seconds(30));
        assertFalse(handle.completion().isDone());

        engine.removeToken("wp2");
        engine.runUntil(seconds(35));
        assertTrue(handle.completion().isDone());
    }

    @Test
    void aMoveToAnUnknownPlaceNeverStarts() {
        engine.placeToken("dps", "wp1", Map.of());
        CommandHandle handle = move("dps", "nowhere");
        engine.runUntil(seconds(30));
        assertFalse(handle.completion().isDone());
        assertEquals(1, engine.snapshot().tokensAt("cmd_move").size());
    }

    @Test
    void stepJumpsToTheEndOfTheNextFiring() {
        assertFalse(engine.step());
        engine.placeToken("oven_platform", "wp1", Map.of());
        burn();
        assertTrue(engine.step());
        assertEquals(seconds(12), engine.now());
    }

    @Test
    void theTraceTiesFiringsToTheirCommand() {
        engine.placeToken("oven_platform", "wp1", Map.of());
        CommandHandle handle = burn();
        engine.runUntil(seconds(12));

        List<EventType> types = engine.events(0).stream()
                .filter(event -> handle.commandId().equals(event.commandId()))
                .map(SimulationEvent::type).toList();
        assertEquals(List.of(EventType.COMMAND_RECEIVED,
                EventType.TRANSITION_STARTED, EventType.TRANSITION_COMPLETED,
                EventType.TRANSITION_STARTED, EventType.TRANSITION_COMPLETED,
                EventType.COMMAND_COMPLETED), types);
    }

    // ---- guards, inhibitors, durations from parameters, results ----

    @Test
    void aParameterMakesTheBurnLonger() {
        engine.placeToken("oven_platform", "wp1", Map.of());
        CommandHandle handle = engine.command("cmd_burn", Map.of("time", "3"), "burn_end", null);
        engine.runUntil(seconds(30));
        assertEquals(seconds(15), handle.completion().join().time());
    }

    @Test
    void sortingByColourFollowsTheGuardAndReportsTheSink() {
        engine.placeToken("sm_start", "wp1", Map.of("color", "red"));
        CommandHandle handle = engine.command("cmd_sort", Map.of("location", "none"), "sort_red_by_color", null);
        engine.runUntil(seconds(10));

        assertEquals(List.of("wp1"), idsAt("sink_2"));
        assertEquals(Map.of("sink", "2"), handle.completion().join().data().get("result"));
    }

    @Test
    void aPredefinedSinkComesFromTheRequest() {
        engine.placeToken("sm_start", "wp1", Map.of("color", "red"));
        CommandHandle handle = engine.command("cmd_sort", Map.of("location", "sink_1"), "sort_predefined", null);
        engine.runUntil(seconds(10));

        assertEquals(List.of("wp1"), idsAt("sink_1"));
        assertEquals(Map.of("sink", "null"), handle.completion().join().data().get("result"));
    }

    @Test
    void detectionReportsTheColourOrNothingWhenThePlaceIsEmpty() {
        CommandHandle nothing = engine.command("cmd_detect", Map.of(), "detect_nothing", null);
        engine.runUntil(seconds(5));
        assertEquals(Map.of("color", "none"), nothing.completion().join().data().get("result"));

        engine.placeToken("sm_start", "wp1", Map.of("color", "blue"));
        CommandHandle blue = engine.command("cmd_detect", Map.of(), "detect", null);
        engine.runUntil(seconds(10));
        assertEquals(Map.of("color", "blue"), blue.completion().join().data().get("result"));
        assertEquals(List.of("wp1"), idsAt("sm_start"));
    }

    @Test
    void theWarehouseHandsOutAWorkpieceOfTheRequestedColour() {
        engine.placeToken("rack", "white1", Map.of("color", "white"));
        engine.placeToken("rack", "red1", Map.of("color", "red"));

        CommandHandle handle = engine.command("cmd_unload", Map.of("color", "red"), "unload", null);
        engine.runUntil(seconds(3));

        assertTrue(handle.completion().isDone());
        assertEquals(List.of("red1"), idsAt("hbw_out"));
        assertEquals(List.of("white1"), idsAt("rack"));
    }

    @Test
    void anUnloadWaitsWhileNoWorkpieceOfThatColourIsStored() {
        engine.placeToken("rack", "white1", Map.of("color", "white"));
        CommandHandle handle = engine.command("cmd_unload", Map.of("color", "blue"), "unload", null);
        engine.runUntil(seconds(30));
        assertFalse(handle.completion().isDone());
    }

    @Test
    void aRequestCanEndInSeveralWaysAndGuardsCanBeNegated() {
        NetDefinition net = NetDefinition.of("ends", List.of(Place.of("cmd"), new Place("idle", 1, 1, null),
                        Place.of("start"), Place.of("end")),
                List.of(new Transition("carry", List.of(Arc.of("cmd", "job"), Arc.of("idle"), Arc.of("${from}", "wp")),
                                List.of(Arc.of("end", "wp"), Arc.of("idle")), seconds(4), null, null,
                                Map.of("mode", "!skip"), null, null, 0, null),
                        new Transition("carry_nothing", List.of(Arc.of("cmd", "job"), Arc.of("idle")),
                                List.of(Arc.of("idle")), seconds(2), null, null, null, List.of("${from}"), null, 0, null)),
                List.of());
        try (SimulationEngine other = new SimulationEngine(UUID.randomUUID(), net)) {
            other.start();
            CommandHandle empty = other.command("cmd", Map.of("from", "start", "mode", "go"),
                    List.of("carry", "carry_nothing"), null);
            other.runUntil(seconds(2));
            assertEquals("carry_nothing", empty.completion().join().subject());

            other.placeToken("start", "wp1", Map.of());
            CommandHandle full = other.command("cmd", Map.of("from", "start", "mode", "go"),
                    List.of("carry", "carry_nothing"), null);
            other.runUntil(seconds(6));
            assertEquals("carry", full.completion().join().subject());

            other.placeToken("start", "wp2", Map.of());
            CommandHandle skipped = other.command("cmd", Map.of("from", "start", "mode", "skip"),
                    List.of("carry", "carry_nothing"), null);
            other.runUntil(seconds(30));
            assertFalse(skipped.completion().isDone());
        }
    }

    // ---- sensors ----

    @Test
    void sensorsAreDerivedFromTheMarking() {
        engine.placeToken("dps", "wp1", Map.of());
        engine.placeToken("oven_platform", "wp2", Map.of("color", "blue"));

        Map<String, Object> sensors = engine.readSensors();
        assertEquals(true, sensors.get("oven_light_barrier"));
        assertEquals(0, sensors.get("dps_raw"));
        assertEquals("blue", sensors.get("platform_color"));
        assertEquals(0, sensors.get("burn_queue"));
    }

    @Test
    void aStuckSensorKeepsItsValue() {
        engine.setSensorFault("oven_light_barrier", SensorFault.stuck());
        engine.placeToken("oven_platform", "wp1", Map.of());
        assertEquals(false, engine.readSensors().get("oven_light_barrier"));

        engine.setSensorFault("oven_light_barrier", null);
        assertEquals(true, engine.readSensors().get("oven_light_barrier"));
    }

    @Test
    void aDroppedOutSensorReadsNull() {
        engine.setSensorFault("oven_light_barrier", SensorFault.dropout());
        assertNull(engine.readSensors().get("oven_light_barrier"));
    }

    @Test
    void aWrongSensorReportsTheOppositeOrAFixedValue() {
        engine.setSensorFault("oven_light_barrier", SensorFault.wrongValue(null));
        assertEquals(true, engine.readSensors().get("oven_light_barrier"));
        engine.setSensorFault("dps_raw", SensorFault.wrongValue(7));
        assertEquals(7, engine.readSensors().get("dps_raw"));
    }

    @Test
    void aDelayedSensorReportsTheOldValue() {
        engine.setSensorFault("oven_light_barrier", SensorFault.delayed(seconds(3)));
        engine.runUntil(seconds(1));
        engine.placeToken("oven_platform", "wp1", Map.of());

        engine.runUntil(seconds(3));
        assertEquals(false, engine.readSensors().get("oven_light_barrier"));
        engine.runUntil(seconds(4));
        assertEquals(true, engine.readSensors().get("oven_light_barrier"));
    }

    // ---- net faults ----

    @Test
    void aBlockedTransitionLeavesTheRequestWaiting() {
        engine.setNetFault("burn_start", NetFault.blocked());
        engine.placeToken("oven_platform", "wp1", Map.of());
        CommandHandle handle = burn();

        engine.runUntil(seconds(30));
        assertFalse(handle.completion().isDone());
        assertEquals(1, engine.snapshot().tokensAt("cmd_burn").size());

        engine.setNetFault("burn_start", null);
        engine.runUntil(seconds(42));
        assertEquals(seconds(42), handle.completion().join().time());
    }

    @Test
    void aDelayedTransitionTakesLonger() {
        engine.setNetFault("burn_end", NetFault.delayed(2));
        engine.placeToken("oven_platform", "wp1", Map.of());
        CommandHandle handle = burn();
        engine.runUntil(seconds(30));
        assertEquals(seconds(24), handle.completion().join().time());
    }

    @Test
    void tokenLossDropsTheWorkpieceButTheMachineFinishes() {
        engine.setNetFault("move", NetFault.tokenLoss());
        engine.placeToken("dps", "wp1", Map.of());
        CommandHandle handle = move("dps", "oven_platform");
        engine.runUntil(seconds(5));

        assertTrue(handle.completion().isDone());
        assertEquals(List.of(), idsAt("oven_platform"));
        assertEquals(1, engine.snapshot().tokensAt("gripper_idle").size());
        assertTrue(engine.events(0).stream().anyMatch(event -> event.type() == EventType.TOKEN_LOST));
    }

    @Test
    void aMisrouteSendsTheWorkpieceElsewhere() {
        engine.setNetFault("burn_end", NetFault.misroute("scrap"));
        engine.placeToken("oven_platform", "wp1", Map.of());
        burn();
        engine.runUntil(seconds(12));
        assertEquals(List.of("wp1"), idsAt("scrap"));
        assertEquals(List.of(), idsAt("oven_platform"));
    }

    // ---- modules ----

    @Test
    void onlyActiveModulesFire() {
        engine.setActiveModules(Set.of("gripper"));
        engine.placeToken("oven_platform", "wp1", Map.of());
        CommandHandle handle = burn();
        engine.runUntil(seconds(30));
        assertFalse(handle.completion().isDone());

        engine.setActiveModules(Set.of());
        engine.runUntil(seconds(42));
        assertTrue(handle.completion().isDone());
    }

    // ---- lifecycle and errors ----

    @Test
    void commandsNeedARunningSimulation() {
        engine.stop();
        assertThrows(SimulationException.class, this::burn);
        assertEquals(SimulationStatus.STOPPED, engine.status());
    }

    @Test
    void stopPausesFiringsInProgress() {
        engine.placeToken("oven_platform", "wp1", Map.of());
        CommandHandle handle = burn();
        engine.runUntil(seconds(6));
        engine.stop();
        engine.start();
        engine.runUntil(seconds(12));
        assertTrue(handle.completion().isDone());
    }

    @Test
    void resetRestoresTheInitialMarking() {
        engine.placeToken("oven_platform", "wp1", Map.of());
        burn();
        engine.runUntil(seconds(3));
        engine.setNetFault("move", NetFault.blocked());

        engine.reset();
        Snapshot snapshot = engine.snapshot();
        assertEquals(SimulationStatus.CREATED, snapshot.status());
        assertEquals(Duration.ZERO, snapshot.time());
        assertEquals(List.of(), snapshot.tokensAt("oven_platform"));
        assertEquals(1, snapshot.tokensAt("oven_idle").size());
        assertTrue(snapshot.running().isEmpty());
        assertTrue(snapshot.netFaults().isEmpty());
    }

    @Test
    void unknownNamesAreRejected() {
        assertThrows(NotFoundException.class, () -> engine.command("nope", Map.of(), (String) null, null));
        assertThrows(NotFoundException.class, () -> engine.command("cmd_burn", Map.of(), "nope", null));
        assertThrows(NotFoundException.class, () -> engine.setSensorFault("nope", SensorFault.dropout()));
        assertThrows(NotFoundException.class, () -> engine.setNetFault("nope", NetFault.blocked()));
        assertThrows(NotFoundException.class, () -> engine.removeToken("nope"));
    }

    @Test
    void placingIntoAFullPlaceOrADuplicateIdIsRejected() {
        engine.placeToken("oven_platform", "wp1", Map.of());
        assertThrows(IllegalArgumentException.class, () -> engine.placeToken("oven_platform", "wp2", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.placeToken("dps", "wp1", Map.of()));
    }
}
