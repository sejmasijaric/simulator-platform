package ch.hsg.simulation.adapters.fischertechnik;

import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.runtime.SimulationEngine;
import ch.hsg.simulation.systems.fischertechnik.FischertechnikLayoutDefinition;
import ch.hsg.simulation.systems.fischertechnik.SortingMachine;
import ch.hsg.simulation.systems.fischertechnik.Transport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FischertechnikMqttPayloadsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 1, 16, 23, 32, 64_000_000);

    @Test
    void idleFactoryLooksLikeTheRecordedMessages() {
        try (SimulationEngine engine = engine()) {
            Map<String, Object> vgr = FischertechnikMqttPayloads.payload("VGR_1", engine.snapshot(), NOW);

            assertThat(vgr).containsEntry("station", "VGR_1")
                    .containsEntry("timestamp", "2026-04-01 16:23:32.06")
                    .containsEntry("i7_light_barrier", 1)
                    .containsEntry("current_state", "ready")
                    .containsEntry("current_task", "")
                    .containsEntry("current_task_duration", 0.0)
                    .containsKeys("id", "i8_color_sensor", "m1_speed", "current_pos_x", "target_pos_z");
            assertThat(FischertechnikMqttPayloads.payload("SM_1", engine.snapshot(), NOW))
                    .containsEntry("i2_color_sensor", 1766)
                    .containsKeys("i1_light_barrier", "i3_light_barrier", "i6_light_barrier", "i7_light_barrier", "i8_light_barrier");
        }
    }

    @Test
    void workpieceInterruptsLightBarrierAndBusyMachineReportsItsTask() {
        try (SimulationEngine engine = engine()) {
            engine.placeEntity("wp", "workpiece", Map.of("color", "red"), FischertechnikLayoutDefinition.DELIVERY_PICK_UP_STATION);
            assertThat(FischertechnikMqttPayloads.payload("VGR_1", engine.snapshot(), NOW)).containsEntry("i7_light_barrier", 0);

            engine.execute("wt_1", ComponentAction.of(Transport.PICK_UP_AND_TRANSPORT, Map.of("start", "oven", "end", "milling_machine")));
            engine.runUntil(Duration.ofSeconds(3));

            assertThat(FischertechnikMqttPayloads.payload("WT_1", engine.snapshot(), NOW))
                    .containsEntry("current_state", "not ready")
                    .containsEntry("current_task", "picking up and transporting the workpiece from the oven to the milling_machine")
                    .containsEntry("current_task_duration", 3.0)
                    .containsEntry("m2_speed", 512);
        }
    }

    @Test
    void colourSensorReportsTheDetectedColourWhileTheWorkpieceIsThere() {
        try (SimulationEngine engine = engine()) {
            engine.placeEntity("wp", "workpiece", Map.of("color", "white"), FischertechnikLayoutDefinition.SORTING_START);
            engine.execute("sm_1", ComponentAction.of(SortingMachine.DETECT_COLOR));
            engine.runUntilIdle();

            Map<String, Object> sm = FischertechnikMqttPayloads.payload("SM_1", engine.snapshot(), NOW);
            assertThat(sm).containsEntry("i2_color_sensor", 1000).containsEntry("i1_light_barrier", 1).containsEntry("i3_light_barrier", 0);
        }
    }

    private static SimulationEngine engine() {
        SimulationEngine engine = new SimulationEngine(new FischertechnikLayoutDefinition().create(UUID.randomUUID()));
        engine.start();
        return engine;
    }
}
