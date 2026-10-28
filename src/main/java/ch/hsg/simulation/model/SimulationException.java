package ch.hsg.simulation.model;

/** Base class for all errors raised by the simulation core. */
public class SimulationException extends RuntimeException {

    public SimulationException(String message) {
        super(message);
    }

    public SimulationException(String message, Throwable cause) {
        super(message, cause);
    }
}
