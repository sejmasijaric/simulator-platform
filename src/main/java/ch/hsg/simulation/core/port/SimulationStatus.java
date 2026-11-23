package ch.hsg.simulation.core.port;

/** CREATED → RUNNING ⇄ STOPPED; reset goes back to CREATED. Only RUNNING accepts commands and moves time. */
public enum SimulationStatus {
    CREATED,
    RUNNING,
    STOPPED
}
