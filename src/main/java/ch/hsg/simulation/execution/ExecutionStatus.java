package ch.hsg.simulation.execution;

public enum ExecutionStatus {
    /** Accepted; at least one phase completes later in simulated time. */
    ACCEPTED,
    /** Not executed because the request was invalid or the component could not take it. */
    REJECTED,
    /** Executed instantly; all changes are committed at the current simulated time. */
    COMPLETED,
    /** The component tried to execute the action and failed; no changes are committed. */
    FAILED
}
