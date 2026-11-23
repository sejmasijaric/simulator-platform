package ch.hsg.simulation.core;

/** A request the simulation cannot carry out in its current state, e.g. a command while it is stopped. */
public class SimulationException extends RuntimeException {

    public SimulationException(String message) {
        super(message);
    }

    public SimulationException(String message, Throwable cause) {
        super(message, cause);
    }
}
