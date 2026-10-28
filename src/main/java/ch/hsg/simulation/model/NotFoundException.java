package ch.hsg.simulation.model;

/** An unknown definition, simulation, component, entity or fault was referenced. */
public class NotFoundException extends SimulationException {

    public NotFoundException(String message) {
        super(message);
    }
}
