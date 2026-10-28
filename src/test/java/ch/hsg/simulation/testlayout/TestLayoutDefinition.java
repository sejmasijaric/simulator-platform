package ch.hsg.simulation.testlayout;

import ch.hsg.simulation.api.SimulationDefinition;
import ch.hsg.simulation.model.Entity;
import ch.hsg.simulation.model.Simulation;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal layout: IN -> HOLD -> OUT, plus an unconnected slot X. Transporter T moves entities,
 * light barrier LB watches OUT. One workpiece starts at IN.
 */
public final class TestLayoutDefinition implements SimulationDefinition {

    public static final String ID = "test-layout";
    public static final Duration PHASE = Duration.ofSeconds(2);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Test layout";
    }

    @Override
    public String getDescription() {
        return "Three connected slots, a transporter and a light barrier";
    }

    @Override
    public Simulation create(UUID simulationId) {
        return new Simulation(simulationId, ID, getName())
                .addComponent(new Slot("IN"))
                .addComponent(new Slot("HOLD"))
                .addComponent(new Slot("OUT"))
                .addComponent(new Slot("X"))
                .addComponent(new Transporter("T", "HOLD", PHASE))
                .addComponent(new LightBarrier("LB", "OUT"))
                .connect("IN", "HOLD")
                .connect("HOLD", "OUT")
                .addEntity(new Entity("wp-1", "workpiece", Map.of("color", "red"), "IN"));
    }
}
