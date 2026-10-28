package ch.hsg.simulation.model;

/** A proposed state change violates the model's invariants; nothing of the batch was applied. */
public class InvalidStateChangeException extends SimulationException {

    public InvalidStateChangeException(String message) {
        super(message);
    }
}
