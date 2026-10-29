package ch.hsg.simulation.systems.demo;

import ch.hsg.simulation.api.EntityTemplate;
import ch.hsg.simulation.api.LayoutView;
import ch.hsg.simulation.api.LayoutView.Point;
import ch.hsg.simulation.api.SimulationDefinition;
import ch.hsg.simulation.model.Entity;
import ch.hsg.simulation.model.Simulation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Small layout to try the platform without hardware: a transporter moves workpieces from IN via
 * HOLD to OUT, a conveyor carries them from OUT to END. Light barriers watch OUT and END.
 */
public final class DemoLayoutDefinition implements SimulationDefinition {

    public static final String ID = "demo";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Demo line";
    }

    @Override
    public String getDescription() {
        return "A transporter and a conveyor move workpieces from IN to END. No hardware needed.";
    }

    @Override
    public Optional<LayoutView> getLayoutView() {
        return Optional.of(new LayoutView(900, 420, null, Map.of(
                "IN", new Point(110, 300),
                "HOLD", new Point(290, 100),
                "OUT", new Point(470, 300),
                "END", new Point(790, 300),
                "T", new Point(290, 250),
                "C", new Point(630, 380),
                "LB_OUT", new Point(470, 210),
                "LB_END", new Point(790, 210))));
    }

    @Override
    public List<EntityTemplate> getEntityTemplates() {
        return List.of(
                new EntityTemplate("Red workpiece", "workpiece", Map.of("color", "red")),
                new EntityTemplate("White workpiece", "workpiece", Map.of("color", "white")),
                new EntityTemplate("Blue workpiece", "workpiece", Map.of("color", "blue")));
    }

    @Override
    public Simulation create(UUID simulationId) {
        return new Simulation(simulationId, ID, getName())
                .addComponent(new Slot("IN"))
                .addComponent(new Slot("HOLD"))
                .addComponent(new Slot("OUT"))
                .addComponent(new Slot("END"))
                .addComponent(new Transporter("T", "HOLD", Duration.ofSeconds(3)))
                .addComponent(new Conveyor("C", "OUT", "END", Duration.ofSeconds(4)))
                .addComponent(new LightBarrier("LB_OUT", "OUT"))
                .addComponent(new LightBarrier("LB_END", "END"))
                .connect("IN", "HOLD")
                .connect("HOLD", "OUT")
                .connect("OUT", "END")
                .addEntity(new Entity("workpiece-red", "workpiece", Map.of("color", "red"), "IN"));
    }
}
