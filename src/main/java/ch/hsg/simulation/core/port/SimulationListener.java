package ch.hsg.simulation.core.port;

import ch.hsg.simulation.core.event.SimulationEvent;

import java.util.UUID;

/**
 * Driven port: what happens inside simulations, for adapters such as an event publisher or a UI
 * stream. Called on the simulation's own thread; must not block or call back synchronously.
 */
@FunctionalInterface
public interface SimulationListener {

    void onEvent(UUID simulationId, String layoutId, SimulationEvent event);
}
