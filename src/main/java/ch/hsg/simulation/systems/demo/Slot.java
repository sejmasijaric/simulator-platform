package ch.hsg.simulation.systems.demo;

import ch.hsg.simulation.api.Capability;
import ch.hsg.simulation.api.Component;

import java.util.Set;

/** Passive position that holds at most one entity. */
public record Slot(String getId) implements Component {

    @Override
    public String getType() {
        return "slot";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of();
    }

    @Override
    public int getCapacity() {
        return 1;
    }
}
