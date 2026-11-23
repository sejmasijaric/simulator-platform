package ch.hsg.simulation.systems.dobot;

import ch.hsg.simulation.api.EntityTemplate;
import ch.hsg.simulation.api.LayoutView;
import ch.hsg.simulation.api.LayoutView.Point;
import ch.hsg.simulation.api.SimulationDefinition;
import ch.hsg.simulation.model.Simulation;
import ch.hsg.simulation.systems.common.Conveyor;
import ch.hsg.simulation.systems.common.Rack;
import ch.hsg.simulation.systems.common.Slot;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dobot Magician with suction cup next to a conveyor belt. The arm runs the lab program
 * ({@code dobot-main}: {@code app.py}, {@code dobot.py}): eight stone positions ({@code pos1} …
 * {@code pos8}), a handover point into the Fischertechnik factory ({@code factory}) and a
 * {@code table} for stones released anywhere else. See docs/layouts/dobot.
 *
 * <p>The conveyor is a placeholder: the lab code does not drive it, so its control, length,
 * sensors and where the arm reaches it are open. It runs through the generic control API only
 * and the arm cannot reach it yet. All durations are guesses until timed in the lab.
 */
public final class DobotLayoutDefinition implements SimulationDefinition {

    public static final String ID = "dobot-conveyor";

    public static final String ARM = "dobot_1";
    public static final String FACTORY = "factory";
    public static final String TABLE = "table";
    public static final String CONVEYOR = "conveyor_1";
    public static final String CONVEYOR_START = "conveyor_start";
    public static final String CONVEYOR_END = "conveyor_end";

    static final DobotArm.Timing ARM_TIMING = new DobotArm.Timing(Duration.ofMillis(200), 100, Duration.ofSeconds(20));
    static final Duration CONVEYOR_RUN = Duration.ofSeconds(5);
    static final int TABLE_CAPACITY = 50;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Dobot + conveyor";
    }

    @Override
    public String getDescription() {
        return "Dobot Magician picking stones from eight positions into the factory, next to a conveyor belt. "
                + "Applications use the Dobot's HTTP API (/move_to, /pick_up_and_transport, /run_xml) on this server.";
    }

    @Override
    public Optional<LayoutView> getLayoutView() {
        // Pixels of web/public/layouts/dobot-conveyer.png (product photo, background removed). The photo
        // shows neither the stone grid nor the factory, so those are placed in free areas.
        Map<String, Point> positions = new HashMap<>(Map.of(
                ARM, new Point(305, 205),
                CONVEYOR, new Point(390, 335),
                CONVEYOR_START, new Point(95, 268),
                CONVEYOR_END, new Point(640, 262),
                FACTORY, new Point(90, 70),
                TABLE, new Point(470, 70)));
        for (String position : DobotArm.STONE_POSITIONS.keySet()) {
            int number = Integer.parseInt(position.substring(3));
            // Odd positions are the row closer to the arm, pairs go outwards: pos1/pos2, pos3/pos4, …
            positions.put(position, new Point(745 + 45 * ((number - 1) / 2), number % 2 == 1 ? 262 : 318));
        }
        return Optional.of(new LayoutView(930, 357, "/layouts/dobot-conveyer.png", positions));
    }

    @Override
    public List<EntityTemplate> getEntityTemplates() {
        return List.of(
                new EntityTemplate("Red stone", "stone", Map.of("color", "red")),
                new EntityTemplate("Yellow stone", "stone", Map.of("color", "yellow")),
                new EntityTemplate("Blue stone", "stone", Map.of("color", "blue")),
                new EntityTemplate("Green stone", "stone", Map.of("color", "green")));
    }

    @Override
    public Simulation create(UUID simulationId) {
        Map<String, Pose> places = new HashMap<>(DobotArm.STONE_POSITIONS);
        places.put(FACTORY, DobotArm.FACTORY_DROP);

        Simulation simulation = new Simulation(simulationId, ID, getName())
                .addComponent(new DobotArm(ARM, places, TABLE, ARM_TIMING))
                .addComponent(new Slot(FACTORY))
                .addComponent(new Rack(TABLE, TABLE_CAPACITY))
                .addComponent(new Slot(CONVEYOR_START))
                .addComponent(new Slot(CONVEYOR_END))
                .addComponent(new Conveyor(CONVEYOR, CONVEYOR_START, CONVEYOR_END, CONVEYOR_RUN));
        DobotArm.STONE_POSITIONS.keySet().forEach(position -> simulation.addComponent(new Slot(position)));
        places.keySet().forEach(place -> simulation.connectBothWays(place, ARM));
        return simulation
                .connect(ARM, TABLE)
                .connect(CONVEYOR_START, CONVEYOR_END);
    }
}
