package ch.hsg.simulation.adapters.fischertechnik;

import ch.hsg.simulation.model.ComponentActivity;
import ch.hsg.simulation.model.ComponentView;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.systems.fischertechnik.FischertechnikLayoutDefinition;
import ch.hsg.simulation.systems.fischertechnik.SortingMachine;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the per-station MQTT payloads of the physical factory from a simulation snapshot, field
 * by field as in {@code hardware/generic/*_txts.py}, see docs/layouts/fischertechnik/mqtt.md.
 * Light barriers are raw inputs: 1 = free, 0 = interrupted. Inputs the model does not simulate
 * (axis switches, valves) report the values recorded from the idle factory. Unlike the physical
 * WT_1, which always reports {@code ready}, WT_1 reports its real status.
 */
final class FischertechnikMqttPayloads {

    /** Station name in topic and payload → component id. HBW_1 is not simulated. */
    static final Map<String, String> STATIONS = Map.of(
            "VGR_1", FischertechnikLayoutDefinition.VGR,
            "OV_1", FischertechnikLayoutDefinition.OVEN,
            "WT_1", FischertechnikLayoutDefinition.WT,
            "MM_1", FischertechnikLayoutDefinition.MILLING,
            "SM_1", FischertechnikLayoutDefinition.SORTING);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS");
    private static final int MOTOR_ON = 512;
    /** Colour sensor brightness: lower is lighter. Values inside the physical thresholds. */
    private static final Map<String, Integer> BRIGHTNESS = Map.of("white", 1000, "red", 1350, "blue", 1560);
    private static final int NO_WORKPIECE_BRIGHTNESS = 1766;
    private static final int VGR_IDLE_BRIGHTNESS = 1575;

    private FischertechnikMqttPayloads() {
    }

    static Map<String, Object> payload(String station, SimulationSnapshot snapshot, LocalDateTime now) {
        ComponentView machine = snapshot.component(STATIONS.get(station)).orElseThrow();
        boolean busy = !machine.activities().isEmpty();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("station", station);
        payload.put("timestamp", TIMESTAMP.format(now));
        switch (station) {
            case "VGR_1" -> {
                payload.put("i1_pos_switch", 0);
                payload.put("i2_pos_switch", 1);
                payload.put("i3_pos_switch", 1);
                payload.put("i7_light_barrier", lightBarrier(snapshot, "vgr_1_lb7"));
                payload.put("i4_light_barrier", lightBarrier(snapshot, "vgr_1_lb4"));
                payload.put("i8_color_sensor", VGR_IDLE_BRIGHTNESS);
                payload.put("o7_compressor_level", busy ? MOTOR_ON : 0);
                payload.put("o8_valve_open", busy ? MOTOR_ON : 0);
                payload.put("m1_speed", busy ? MOTOR_ON : 0);
                payload.put("m2_speed", busy ? MOTOR_ON : 0);
                payload.put("m3_speed", busy ? MOTOR_ON : 0);
                status(payload, machine, snapshot);
                payload.put("current_pos_x", 0);
                payload.put("current_pos_y", 0);
                payload.put("current_pos_z", 0);
                payload.put("target_pos_x", 0);
                payload.put("target_pos_y", 0);
                payload.put("target_pos_z", 0);
            }
            case "OV_1" -> {
                boolean inside = String.valueOf(machine.state().attributes().get("current_sub_task")).startsWith("burning");
                payload.put("i1_pos_switch", inside ? 1 : 0);
                payload.put("i2_pos_switch", busy && !inside ? 1 : 0);
                payload.put("i5_light_barrier", lightBarrier(snapshot, "ov_1_lb5"));
                payload.put("m1_speed", busy && !inside ? MOTOR_ON : 0);
                payload.put("o7_valve", 0);
                payload.put("o8_compressor", 0);
                status(payload, machine, snapshot);
            }
            case "WT_1" -> {
                payload.put("i3_pos_switch", 0);
                payload.put("i4_pos_switch", 0);
                payload.put("m2_speed", busy ? MOTOR_ON : 0);
                payload.put("o5_valve", 0);
                payload.put("o6_valve", 0);
                payload.put("o8_compressor", 0);
                status(payload, machine, snapshot);
            }
            case "MM_1" -> {
                payload.put("i1_pos_switch", busy ? 0 : 1);
                payload.put("i2_pos_switch", 0);
                payload.put("i3_pos_switch", 0);
                payload.put("i4_light_barrier", lightBarrier(snapshot, "mm_1_lb4"));
                payload.put("m1_speed", busy ? MOTOR_ON : 0);
                payload.put("m2_speed", 0);
                payload.put("m3_speed", busy ? MOTOR_ON : 0);
                payload.put("o7_valve", 0);
                payload.put("o8_compressor", 0);
                status(payload, machine, snapshot);
            }
            case "SM_1" -> {
                payload.put("i1_light_barrier", lightBarrier(snapshot, "sm_1_lb1"));
                payload.put("i2_color_sensor", colorSensor(snapshot, machine));
                payload.put("i3_light_barrier", lightBarrier(snapshot, "sm_1_lb3"));
                payload.put("i6_light_barrier", lightBarrier(snapshot, "sm_1_lb6"));
                payload.put("i7_light_barrier", lightBarrier(snapshot, "sm_1_lb7"));
                payload.put("i8_light_barrier", lightBarrier(snapshot, "sm_1_lb8"));
                payload.put("m1_speed", busy ? MOTOR_ON : 0);
                payload.put("o5_valve", 0);
                payload.put("o6_valve", 0);
                payload.put("o7_valve", 0);
                payload.put("o8_compressor", 0);
                status(payload, machine, snapshot);
            }
            default -> throw new IllegalArgumentException("Unknown station " + station);
        }
        return payload;
    }

    private static void status(Map<String, Object> payload, ComponentView machine, SimulationSnapshot snapshot) {
        Map<String, Object> state = machine.state().attributes();
        Optional<ComponentActivity> activity = machine.activities().stream().findFirst();
        payload.put("current_state", state.get("current_state"));
        payload.put("current_task", state.get("current_task"));
        payload.put("current_task_duration", activity
                .map(running -> seconds(snapshot.time().minus(running.startedAt())))
                .orElse(0.0));
        payload.put("current_sub_task", state.get("current_sub_task"));
    }

    private static int lightBarrier(SimulationSnapshot snapshot, String id) {
        return Boolean.TRUE.equals(snapshot.component(id).orElseThrow().state().attributes().get("interrupted")) ? 0 : 1;
    }

    /** The sensor sees the workpiece at the colour sensor position with the colour the machine detected. */
    private static int colorSensor(SimulationSnapshot snapshot, ComponentView sortingMachine) {
        boolean workpieceAtSensor = snapshot.entities().stream()
                .anyMatch(entity -> FischertechnikLayoutDefinition.COLOR_SENSOR.equals(entity.locationComponentId()));
        Object color = sortingMachine.state().attributes().get(SortingMachine.LAST_COLOR);
        return workpieceAtSensor ? BRIGHTNESS.getOrDefault(String.valueOf(color), NO_WORKPIECE_BRIGHTNESS) : NO_WORKPIECE_BRIGHTNESS;
    }

    private static double seconds(Duration duration) {
        return Math.round(duration.toNanos() / 1_000.0) / 1_000_000.0;
    }
}
