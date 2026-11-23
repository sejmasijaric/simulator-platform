package ch.hsg.simulation.core.event;

public enum EventType {
    SIMULATION_STARTED,
    SIMULATION_STOPPED,
    SIMULATION_RESET,
    /** a request token was put into a command place */
    COMMAND_RECEIVED,
    /** the transition a request waits for has fired for it */
    COMMAND_COMPLETED,
    /** a transition took its input tokens */
    TRANSITION_STARTED,
    /** a transition put its output tokens */
    TRANSITION_COMPLETED,
    /** a token was placed or removed by hand (control) */
    TOKEN_PLACED,
    TOKEN_REMOVED,
    /** a token disappeared because of a token-loss fault */
    TOKEN_LOST,
    FAULT_ACTIVATED,
    FAULT_CLEARED,
    MODULES_CHANGED
}
