package ch.hsg.simulation.core.port;

import ch.hsg.simulation.core.net.NetDefinition;

import java.util.List;

/** Driven port: where layouts come from. Implemented by an adapter, e.g. one reading YAML files. */
@FunctionalInterface
public interface LayoutSource {

    List<NetDefinition> load();
}
