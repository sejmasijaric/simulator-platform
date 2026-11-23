package ch.hsg.simulation.systems;

import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ExecutionStatus;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.runtime.SimulationEngine;
import ch.hsg.simulation.systems.common.Conveyor;
import ch.hsg.simulation.systems.dobot.DobotArm;
import ch.hsg.simulation.systems.dobot.DobotLayoutDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static ch.hsg.simulation.systems.dobot.DobotLayoutDefinition.ARM;
import static org.assertj.core.api.Assertions.assertThat;

class DobotLayoutTest {

    @Test
    void picksStoneAndPlacesItIntoTheFactory() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("stone", "stone", Map.of("color", "red"), "pos1");

            run(engine, DobotArm.PICK_UP_AND_TRANSPORT, Map.of("start", "pos1", "end", "factory"));

            assertThat(location(engine.snapshot())).isEqualTo("factory");
            assertThat(arm(engine.snapshot())).containsEntry("x", DobotArm.HOME.x()).containsEntry("suction", false)
                    .containsEntry("current_task", "");
        }
    }

    @Test
    void stoneIsOnTheArmWhileItMoves() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("stone", "stone", Map.of("color", "red"), "pos3");
            engine.execute(ARM, ComponentAction.of(DobotArm.PICK_UP_AND_TRANSPORT, Map.of("start", "pos3", "end", "pos8")));

            engine.runUntil(Duration.ofSeconds(2));
            assertThat(location(engine.snapshot())).isEqualTo(ARM);
            assertThat(arm(engine.snapshot())).containsEntry("suction", true);

            engine.runUntilIdle();
            assertThat(location(engine.snapshot())).isEqualTo("pos8");
        }
    }

    @Test
    void picksAtCoordinatesOfAStonePosition() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("stone", "stone", Map.of("color", "blue"), "pos2");

            run(engine, DobotArm.PICK_UP_AND_TRANSPORT, Map.of("start", "272,63,-74,0", "end", "POS5"));

            assertThat(location(engine.snapshot())).isEqualTo("pos5");
        }
    }

    @Test
    void releasingAnywhereElseDropsTheStoneOnTheTable() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("stone", "stone", Map.of("color", "red"), "pos1");
            engine.placeEntity("other", "stone", Map.of("color", "blue"), "pos2");

            run(engine, DobotArm.PICK_UP_AND_TRANSPORT, Map.of("start", "pos1", "end", "pos2"));

            assertThat(location(engine.snapshot())).isEqualTo(DobotLayoutDefinition.TABLE);
        }
    }

    @Test
    void pickingFromTheFactoryPicksNothingLikeTheLabCode() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("stone", "stone", Map.of("color", "red"), "factory");

            run(engine, DobotArm.PICK_UP_AND_TRANSPORT, Map.of("start", "factory", "end", "pos1"));

            assertThat(location(engine.snapshot())).isEqualTo("factory");
        }
    }

    @Test
    void moveToNeverMovesStones() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("stone", "stone", Map.of("color", "red"), "pos1");

            run(engine, DobotArm.MOVE_TO, Map.of("start", "pos1", "end", "100,100,50,0"));

            assertThat(location(engine.snapshot())).isEqualTo("pos1");
            assertThat(arm(engine.snapshot())).containsEntry("x", 100.0).containsEntry("z", 50.0);
        }
    }

    @Test
    void missesStoneWhenFaultIsActive() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("stone", "stone", Map.of("color", "red"), "pos1");
            engine.setFault(ARM, DobotArm.MISSES_STONE, true);

            run(engine, DobotArm.PICK_UP_AND_TRANSPORT, Map.of("start", "pos1", "end", "factory"));

            assertThat(location(engine.snapshot())).isEqualTo("pos1");
        }
    }

    @Test
    void invalidPositionsAreRejectedWithTheLabMessages() {
        try (SimulationEngine engine = startedEngine()) {
            var unknown = engine.execute(ARM, ComponentAction.of(DobotArm.MOVE_TO, Map.of("start", "pos9", "end", "pos1")));
            assertThat(unknown.status()).isEqualTo(ExecutionStatus.REJECTED);
            assertThat(unknown.errorMessage()).contains("Invalid position format: could not convert string to float: 'pos9'");

            var tooShort = engine.execute(ARM, ComponentAction.of(DobotArm.MOVE_TO, Map.of("start", "1,2,3", "end", "pos1")));
            assertThat(tooShort.errorMessage()).contains("Invalid position format: Exactly 4 values (x,y,z,r) required");
        }
    }

    @Test
    void playbackMovesThroughItsPointsAndReturnsHome() {
        String playback = "<root><DobotType><item_0>Magician</item_0></DobotType>"
                + "<row_StudioVersion><item_0>Ver-192</item_0></row_StudioVersion>"
                + "<row0><item_0>1</item_0><item_1 /><item_2>275.1176</item_2><item_3>65.6466</item_3>"
                + "<item_4>-90.5743</item_4><item_5>14.497</item_5></row0>"
                + "<row1><item_2>abc</item_2><item_3>1</item_3><item_4>1</item_4><item_5>1</item_5></row1></root>";
        try (SimulationEngine engine = startedEngine()) {
            engine.execute(ARM, ComponentAction.of(DobotArm.RUN_PLAYBACK, Map.of("playback", playback)));

            engine.runUntil(Duration.ofSeconds(24));
            assertThat(arm(engine.snapshot())).containsEntry("x", 275.1176);

            engine.runUntilIdle();
            assertThat(arm(engine.snapshot())).containsEntry("x", DobotArm.HOME.x()).containsEntry("current_task", "");
        }
    }

    @Test
    void conveyorCarriesAStoneToItsEnd() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("stone", "stone", Map.of("color", "yellow"), DobotLayoutDefinition.CONVEYOR_START);

            var result = engine.execute(DobotLayoutDefinition.CONVEYOR, ComponentAction.of(Conveyor.RUN));
            assertThat(result.isExecuted()).isTrue();
            engine.runUntilIdle();

            assertThat(location(engine.snapshot())).isEqualTo(DobotLayoutDefinition.CONVEYOR_END);
        }
    }

    @Test
    void layoutViewCoversAllComponents() {
        DobotLayoutDefinition definition = new DobotLayoutDefinition();
        var positions = definition.getLayoutView().orElseThrow().positions();
        definition.create(UUID.randomUUID()).getComponents()
                .forEach(component -> assertThat(positions).containsKey(component.getId()));
    }

    private static SimulationEngine startedEngine() {
        SimulationEngine engine = new SimulationEngine(new DobotLayoutDefinition().create(UUID.randomUUID()));
        engine.start();
        return engine;
    }

    private static void run(SimulationEngine engine, String capability, Map<String, ?> parameters) {
        var result = engine.execute(ARM, ComponentAction.of(capability, parameters));
        assertThat(result.isExecuted()).as(result.errorMessage().orElse("")).isTrue();
        engine.runUntilIdle();
    }

    private static String location(SimulationSnapshot snapshot) {
        return snapshot.entity("stone").orElseThrow().locationComponentId();
    }

    private static Map<String, Object> arm(SimulationSnapshot snapshot) {
        return snapshot.component(ARM).orElseThrow().state().attributes();
    }
}
