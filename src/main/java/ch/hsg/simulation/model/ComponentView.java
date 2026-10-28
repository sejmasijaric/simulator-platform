package ch.hsg.simulation.model;

import java.util.Set;

/** Read-only view of a component in a snapshot. */
public record ComponentView(
        String id,
        String type,
        ComponentState state,
        Set<String> capabilities,
        Set<String> faultModes,
        Set<String> activeFaults,
        boolean busy) {

    public ComponentView {
        capabilities = Set.copyOf(capabilities);
        faultModes = Set.copyOf(faultModes);
        activeFaults = Set.copyOf(activeFaults);
    }
}
