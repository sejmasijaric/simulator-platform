package ch.hsg.simulation.model;

import ch.hsg.simulation.api.FaultMode;

import java.util.List;
import java.util.Set;

/**
 * Read-only view of a component in a snapshot. {@code capacity} is null when unlimited.
 * {@code activities} are the component's running actions; it is busy while the list is not empty.
 */
public record ComponentView(
        String id,
        String type,
        ComponentState state,
        Set<String> capabilities,
        Integer capacity,
        List<FaultMode> faultModes,
        Set<String> activeFaults,
        List<ComponentActivity> activities) {

    public ComponentView {
        capabilities = Set.copyOf(capabilities);
        faultModes = List.copyOf(faultModes);
        activeFaults = Set.copyOf(activeFaults);
        activities = List.copyOf(activities);
    }

    public boolean busy() {
        return !activities.isEmpty();
    }
}
