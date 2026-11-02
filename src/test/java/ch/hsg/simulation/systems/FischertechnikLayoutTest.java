package ch.hsg.simulation.systems;

import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ExecutionStatus;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.runtime.SimulationEngine;
import ch.hsg.simulation.systems.fischertechnik.FischertechnikLayoutDefinition;
import ch.hsg.simulation.systems.fischertechnik.MillingMachine;
import ch.hsg.simulation.systems.fischertechnik.Oven;
import ch.hsg.simulation.systems.fischertechnik.SortingMachine;
import ch.hsg.simulation.systems.fischertechnik.Transport;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static ch.hsg.simulation.systems.fischertechnik.FischertechnikLayoutDefinition.DELIVERY_PICK_UP_STATION;
import static org.assertj.core.api.Assertions.assertThat;

class FischertechnikLayoutTest {

    @Test
    void labProcessMovesWorkpieceIntoSinkOne() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("wp", "workpiece", Map.of("color", "red"), DELIVERY_PICK_UP_STATION);

            run(engine, "vgr_1", Transport.PICK_UP_AND_TRANSPORT, Map.of("start", DELIVERY_PICK_UP_STATION, "end", "oven"));
            assertThat(location(engine.snapshot())).isEqualTo("oven");
            assertThat(lightBarrier(engine.snapshot(), "ov_1_lb5")).isTrue();
            run(engine, "ov_1", Oven.BURN, Map.of("time", 2));
            run(engine, "wt_1", Transport.PICK_UP_AND_TRANSPORT, Map.of("start", "oven", "end", "milling_machine"));
            run(engine, "mm_1", MillingMachine.MILL, Map.of("start", "initial", "end", "ejection", "time", 10));
            assertThat(location(engine.snapshot())).isEqualTo("sorting_machine_start");
            run(engine, "sm_1", SortingMachine.DETECT_COLOR, Map.of());
            assertThat(engine.snapshot().component("sm_1").orElseThrow().state().attributes())
                    .containsEntry(SortingMachine.LAST_COLOR, "red");
            run(engine, "sm_1", SortingMachine.SORT, sortParameters("sink_1"));

            assertThat(location(engine.snapshot())).isEqualTo("sink_1");
            assertThat(lightBarrier(engine.snapshot(), "sm_1_lb6")).isTrue();
            assertThat(lightBarrier(engine.snapshot(), "vgr_1_lb7")).isFalse();
        }
    }

    @Test
    void sortByColourEjectsIntoMatchingSink() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("wp", "workpiece", Map.of("color", "blue"), "sorting_machine_start");

            run(engine, "sm_1", SortingMachine.SORT, sortParameters("none"));

            assertThat(location(engine.snapshot())).isEqualTo("sink_3");
            assertThat(engine.snapshot().component("sm_1").orElseThrow().state().attributes())
                    .containsEntry(SortingMachine.LAST_SINK, 3);
        }
    }

    @Test
    void sortByColourAfterDetectColorIsRejectedLikeTheRealMachine() {
        try (SimulationEngine engine = startedEngine()) {
            engine.placeEntity("wp", "workpiece", Map.of("color", "blue"), "sorting_machine_start");
            run(engine, "sm_1", SortingMachine.DETECT_COLOR, Map.of());

            var result = engine.execute("sm_1", ComponentAction.of(SortingMachine.SORT, sortParameters("none")));

            assertThat(result.status()).isEqualTo(ExecutionStatus.REJECTED);
            assertThat(result.errorMessage()).contains("'none' is not in list");
        }
    }

    @Test
    void transportFromEmptyPositionRunsWithoutMovingAnything() {
        try (SimulationEngine engine = startedEngine()) {
            run(engine, "vgr_1", Transport.PICK_UP_AND_TRANSPORT, Map.of("start", DELIVERY_PICK_UP_STATION, "end", "oven"));

            assertThat(engine.snapshot().entities()).isEmpty();
            assertThat(engine.snapshot().component("vgr_1").orElseThrow().state().attributes())
                    .containsEntry("current_state", "ready");
        }
    }

    @Test
    void unknownPositionIsRejected() {
        try (SimulationEngine engine = startedEngine()) {
            var result = engine.execute("vgr_1", ComponentAction.of(Transport.PICK_UP_AND_TRANSPORT,
                    Map.of("start", "foo", "end", "oven")));

            assertThat(result.status()).isEqualTo(ExecutionStatus.REJECTED);
            assertThat(result.errorMessage()).contains("Position foo does not exist");
        }
    }

    @Test
    void layoutViewCoversAllComponents() {
        FischertechnikLayoutDefinition definition = new FischertechnikLayoutDefinition();
        var positions = definition.getLayoutView().orElseThrow().positions();
        definition.create(UUID.randomUUID()).getComponents()
                .forEach(component -> assertThat(positions).containsKey(component.getId()));
    }

    private static SimulationEngine startedEngine() {
        SimulationEngine engine = new SimulationEngine(new FischertechnikLayoutDefinition().create(UUID.randomUUID()));
        engine.start();
        return engine;
    }

    private static void run(SimulationEngine engine, String componentId, String capability, Map<String, ?> parameters) {
        var result = engine.execute(componentId, ComponentAction.of(capability, parameters));
        assertThat(result.isExecuted()).as(result.errorMessage().orElse("")).isTrue();
        engine.runUntilIdle();
    }

    private static Map<String, Object> sortParameters(String location) {
        return Map.of("start", "initial", "predefined_ejection_location", location, "use_nfc", false);
    }

    private static String location(SimulationSnapshot snapshot) {
        return snapshot.entity("wp").orElseThrow().locationComponentId();
    }

    private static boolean lightBarrier(SimulationSnapshot snapshot, String id) {
        return (Boolean) snapshot.component(id).orElseThrow().state().attributes().get("interrupted");
    }
}
