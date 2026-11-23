package ch.hsg.simulation.core;

/** An unknown layout, simulation, place, transition, sensor or token. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
