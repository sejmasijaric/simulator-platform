package ch.hsg.simulation.systems.fischertechnik;

import ch.hsg.simulation.api.Capability;
import ch.hsg.simulation.api.Component;

import java.util.Set;

/** Passive storage that holds several workpieces, e.g. the high-bay warehouse rack. */
public record Rack(String getId, int getCapacity) implements Component {

    @Override
    public String getType() {
        return "rack";
    }

    @Override
    public Set<Capability> getCapabilities() {
        return Set.of();
    }
}
