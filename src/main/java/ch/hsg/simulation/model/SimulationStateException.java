package ch.hsg.simulation.model;

/** An operation is not allowed in the simulation's current lifecycle state. */
public class SimulationStateException extends SimulationException {

    public SimulationStateException(String message) {
        super(message);
    }
}
