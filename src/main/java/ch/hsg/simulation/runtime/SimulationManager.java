package ch.hsg.simulation.runtime;

import ch.hsg.simulation.api.SimulationDefinition;
import ch.hsg.simulation.event.SimulationEvent;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.model.NotFoundException;
import ch.hsg.simulation.model.Simulation;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.model.SimulationStateException;
import ch.hsg.simulation.model.SimulationStatus;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry of definitions and of independent simulation runtimes. Creates, looks up, starts,
 * stops, resets and removes simulations, and delegates execution to their engines. Never hands
 * out a mutable {@link Simulation}.
 */
public final class SimulationManager implements AutoCloseable {

    private final Map<String, SimulationDefinition> definitions = new LinkedHashMap<>();
    private final ConcurrentMap<UUID, SimulationEngine> engines = new ConcurrentHashMap<>();

    public SimulationManager(Collection<? extends SimulationDefinition> definitions) {
        for (SimulationDefinition definition : definitions) {
            if (this.definitions.putIfAbsent(definition.getId(), definition) != null) {
                throw new IllegalArgumentException("Duplicate simulation definition id: " + definition.getId());
            }
        }
    }

    public List<SimulationDefinition> getDefinitions() {
        return List.copyOf(definitions.values());
    }

    public SimulationDefinition getDefinition(String definitionId) {
        SimulationDefinition definition = definitions.get(definitionId);
        if (definition == null) {
            throw new NotFoundException("Unknown simulation definition: " + definitionId);
        }
        return definition;
    }

    /** Builds a fresh simulation from the definition, validates it and registers its engine. */
    public UUID create(String definitionId) {
        UUID simulationId = UUID.randomUUID();
        Simulation simulation = build(getDefinition(definitionId), simulationId);
        engines.put(simulationId, new SimulationEngine(simulation));
        return simulationId;
    }

    public SimulationSnapshot getSimulation(UUID simulationId) {
        return engine(simulationId).snapshot();
    }

    public List<SimulationSnapshot> getSimulations() {
        return engines.values().stream()
                .map(SimulationEngine::snapshot)
                .sorted(Comparator.comparing(snapshot -> snapshot.id().toString()))
                .toList();
    }

    public void start(UUID simulationId) {
        engine(simulationId).start();
    }

    public void stop(UUID simulationId) {
        engine(simulationId).stop();
    }

    /** Rebuilds the simulation from its definition, keeping its id; the result is CREATED. */
    public void reset(UUID simulationId) {
        SimulationEngine engine = engine(simulationId);
        String definitionId = engine.snapshot().definitionId();
        engine.reset(build(getDefinition(definitionId), simulationId));
    }

    /** Only allowed when the simulation is not running. */
    public void remove(UUID simulationId) {
        SimulationEngine engine = engine(simulationId);
        if (engine.getStatus() == SimulationStatus.RUNNING) {
            throw new SimulationStateException("Stop simulation " + simulationId + " before removing it");
        }
        engines.remove(simulationId);
        engine.close();
    }

    public ComponentExecutionResult execute(UUID simulationId, String componentId, ComponentAction action) {
        return engine(simulationId).execute(componentId, action);
    }

    public void setFault(UUID simulationId, String componentId, String faultId, boolean active) {
        engine(simulationId).setFault(componentId, faultId, active);
    }

    public List<SimulationEvent> getEvents(UUID simulationId) {
        return engine(simulationId).events();
    }

    /** For adapters (time driving, listeners, awaiting completion). */
    public SimulationEngine engine(UUID simulationId) {
        SimulationEngine engine = engines.get(simulationId);
        if (engine == null) {
            throw new NotFoundException("Unknown simulation: " + simulationId);
        }
        return engine;
    }

    @Override
    public void close() {
        engines.values().forEach(SimulationEngine::close);
        engines.clear();
    }

    private static Simulation build(SimulationDefinition definition, UUID simulationId) {
        Simulation simulation = definition.create(simulationId);
        if (!simulation.getId().equals(simulationId) || !simulation.getDefinitionId().equals(definition.getId())) {
            throw new IllegalStateException("Definition " + definition.getId() + " created a simulation with wrong id or definition id");
        }
        if (simulation.getStatus() != SimulationStatus.CREATED) {
            throw new IllegalStateException("Definition " + definition.getId() + " must create a simulation in status CREATED");
        }
        simulation.validate();
        return simulation;
    }
}
