package ch.hsg.simulation.systems.fischertechnik;

import ch.hsg.simulation.api.EntityTemplate;
import ch.hsg.simulation.api.LayoutView;
import ch.hsg.simulation.api.LayoutView.Point;
import ch.hsg.simulation.api.SimulationDefinition;
import ch.hsg.simulation.model.Simulation;
import ch.hsg.simulation.systems.common.LightBarrier;
import ch.hsg.simulation.systems.common.Rack;
import ch.hsg.simulation.systems.common.Slot;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fischertechnik Factory 4.0: vacuum gripper, oven, workstation transport, milling machine,
 * sorting line and a reduced high-bay warehouse (unload only). Component ids match the {@code machine} parameter
 * of the physical HTTP API ({@code vgr_1}, …), position ids match its position names, and light
 * barrier ids are {@code <machine>_lb<input>} as in the physical MQTT payload.
 * See docs/layouts/fischertechnik.
 *
 * <p>Durations are starting values from the 2021 production log and the controller source; adjust
 * them once the lab recordings are in.
 */
public final class FischertechnikLayoutDefinition implements SimulationDefinition {

    public static final String ID = "fischertechnik";

    public static final String VGR = "vgr_1";
    public static final String OVEN = "ov_1";
    public static final String WT = "wt_1";
    public static final String MILLING = "mm_1";
    public static final String SORTING = "sm_1";
    public static final String WAREHOUSE = "hbw_1";

    public static final String DELIVERY_PICK_UP_STATION = "delivery_pick_up_station";
    public static final String COLOR_DETECTION_STATION = "color_detection_delivery_pick_up_station";
    public static final String WAREHOUSE_STOCK = "high_bay_warehouse_rack";
    public static final String WAREHOUSE_OUTPUT = "high_bay_warehouse";
    public static final String HBW_DELIVERY_STATION = "high_bay_warehouse_delivery_station";
    public static final String OVEN_POSITION = "oven";
    public static final String OVEN_CHAMBER = "oven_chamber";
    public static final String MILLING_POSITION = "milling_machine";
    public static final String MILLING_BELT = "milling_machine_belt";
    public static final String SORTING_START = "sorting_machine_start";
    public static final String COLOR_SENSOR = "color_sensor";
    public static final List<String> SINKS = List.of("sink_1", "sink_2", "sink_3");

    static final Duration VGR_PICK_UP = Duration.ofSeconds(20);
    static final Duration VGR_TOTAL = Duration.ofSeconds(45);
    static final Duration WT_PICK_UP = Duration.ofSeconds(20);
    static final Duration WT_TOTAL = Duration.ofSeconds(44);
    static final Duration OVEN_DRIVE = Duration.ofSeconds(5);
    static final Duration MILLING_TURN = Duration.ofSeconds(8);
    static final Duration MILLING_BELT_RUN = Duration.ofSeconds(6);
    static final Duration SORTING_DETECT = Duration.ofSeconds(5);
    static final Duration SORTING_TO_SINK = Duration.ofSeconds(5);
    static final Duration WAREHOUSE_UNLOAD = Duration.ofSeconds(45);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Fischertechnik Factory 4.0";
    }

    @Override
    public String getDescription() {
        return "Gripper, oven, milling machine and sorting line of the lab factory. "
                + "Applications use the factory's HTTP API (/vgr/..., /ov/..., …) on this server.";
    }

    @Override
    public Optional<LayoutView> getLayoutView() {
        // Coordinates are pixels of web/public/layouts/fischertechnik.png (top-down photo, background removed).
        return Optional.of(new LayoutView(1428, 1000, "/layouts/fischertechnik.png", Map.ofEntries(
                Map.entry(OVEN, new Point(900, 235)),
                Map.entry(WT, new Point(1000, 405)),
                Map.entry(MILLING, new Point(1190, 95)),
                Map.entry(VGR, new Point(870, 700)),
                Map.entry(SORTING, new Point(1350, 385)),
                Map.entry(WAREHOUSE, new Point(430, 300)),
                Map.entry(WAREHOUSE_STOCK, new Point(430, 420)),
                Map.entry(WAREHOUSE_OUTPUT, new Point(640, 760)),
                Map.entry(COLOR_DETECTION_STATION, new Point(930, 960)),
                Map.entry(OVEN_POSITION, new Point(865, 318)),
                Map.entry("ov_1_lb5", new Point(822, 318)),
                Map.entry(OVEN_CHAMBER, new Point(930, 305)),
                Map.entry(MILLING_POSITION, new Point(1130, 250)),
                Map.entry(MILLING_BELT, new Point(1268, 345)),
                Map.entry("mm_1_lb4", new Point(1300, 345)),
                Map.entry(DELIVERY_PICK_UP_STATION, new Point(870, 940)),
                Map.entry("vgr_1_lb7", new Point(830, 940)),
                Map.entry(HBW_DELIVERY_STATION, new Point(995, 930)),
                Map.entry("vgr_1_lb4", new Point(1035, 930)),
                Map.entry(SORTING_START, new Point(1262, 445)),
                Map.entry("sm_1_lb1", new Point(1228, 445)),
                Map.entry(COLOR_SENSOR, new Point(1290, 565)),
                Map.entry("sm_1_lb3", new Point(1290, 655)),
                Map.entry("sink_1", new Point(1180, 715)),
                Map.entry("sm_1_lb6", new Point(1140, 715)),
                Map.entry("sink_2", new Point(1180, 800)),
                Map.entry("sm_1_lb7", new Point(1140, 800)),
                Map.entry("sink_3", new Point(1180, 880)),
                Map.entry("sm_1_lb8", new Point(1140, 880)))));
    }

    @Override
    public List<EntityTemplate> getEntityTemplates() {
        return List.of(
                new EntityTemplate("White workpiece", "workpiece", Map.of("color", "white")),
                new EntityTemplate("Red workpiece", "workpiece", Map.of("color", "red")),
                new EntityTemplate("Blue workpiece", "workpiece", Map.of("color", "blue")));
    }

    @Override
    public Simulation create(UUID simulationId) {
        Set<String> vgrStarts = Set.of(DELIVERY_PICK_UP_STATION, COLOR_DETECTION_STATION, WAREHOUSE_OUTPUT, OVEN_POSITION,
                "sink_1", "sink_2", "sink_3");
        Set<String> vgrEnds = Set.of(DELIVERY_PICK_UP_STATION, COLOR_DETECTION_STATION, WAREHOUSE_OUTPUT, OVEN_POSITION,
                "sink_1", "sink_2", "sink_3", HBW_DELIVERY_STATION);
        Set<String> wtPositions = Set.of(OVEN_POSITION, MILLING_POSITION);

        Simulation simulation = new Simulation(simulationId, ID, getName())
                .addComponent(new Slot(DELIVERY_PICK_UP_STATION))
                .addComponent(new Slot(HBW_DELIVERY_STATION))
                .addComponent(new Slot(COLOR_DETECTION_STATION))
                .addComponent(new Rack(WAREHOUSE_STOCK, 9))
                .addComponent(new Slot(WAREHOUSE_OUTPUT))
                .addComponent(new HighBayWarehouse(WAREHOUSE, WAREHOUSE_STOCK, WAREHOUSE_OUTPUT, WAREHOUSE_UNLOAD))
                .addComponent(new Slot(OVEN_POSITION))
                .addComponent(new Slot(OVEN_CHAMBER))
                .addComponent(new Slot(MILLING_POSITION))
                .addComponent(new Slot(MILLING_BELT))
                .addComponent(new Slot(SORTING_START))
                .addComponent(new Slot(COLOR_SENSOR))
                .addComponent(new Transport(VGR, "vacuum-gripper", vgrStarts, vgrEnds, VGR_PICK_UP, VGR_TOTAL))
                .addComponent(new Oven(OVEN, OVEN_POSITION, OVEN_CHAMBER, OVEN_DRIVE))
                .addComponent(new Transport(WT, "transport", wtPositions, wtPositions, WT_PICK_UP, WT_TOTAL))
                .addComponent(new MillingMachine(MILLING, MILLING_POSITION, MILLING_BELT, SORTING_START, MILLING_TURN, MILLING_BELT_RUN))
                .addComponent(new SortingMachine(SORTING, SORTING_START, COLOR_SENSOR, SINKS, SORTING_DETECT, SORTING_TO_SINK))
                .addComponent(new LightBarrier("vgr_1_lb7", DELIVERY_PICK_UP_STATION))
                .addComponent(new LightBarrier("vgr_1_lb4", HBW_DELIVERY_STATION))
                .addComponent(new LightBarrier("ov_1_lb5", OVEN_POSITION))
                .addComponent(new LightBarrier("mm_1_lb4", MILLING_BELT))
                .addComponent(new LightBarrier("sm_1_lb1", SORTING_START))
                .addComponent(new LightBarrier("sm_1_lb3", COLOR_SENSOR));
        for (int i = 0; i < SINKS.size(); i++) {
            simulation.addComponent(new Slot(SINKS.get(i)))
                    .addComponent(new LightBarrier("sm_1_lb" + (6 + i), SINKS.get(i)))
                    .connect(COLOR_SENSOR, SINKS.get(i));
        }
        vgrStarts.forEach(position -> simulation.connect(position, VGR));
        vgrEnds.forEach(position -> simulation.connect(VGR, position));
        return simulation
                .connect(WAREHOUSE_STOCK, WAREHOUSE_OUTPUT)
                .connectBothWays(OVEN_POSITION, OVEN_CHAMBER)
                .connectBothWays(OVEN_POSITION, WT)
                .connectBothWays(WT, MILLING_POSITION)
                .connect(MILLING_POSITION, MILLING_BELT)
                .connect(MILLING_BELT, SORTING_START)
                .connect(SORTING_START, COLOR_SENSOR);
    }
}
