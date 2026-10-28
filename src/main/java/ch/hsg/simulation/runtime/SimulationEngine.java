package ch.hsg.simulation.runtime;

import ch.hsg.simulation.api.Capability;
import ch.hsg.simulation.api.Component;
import ch.hsg.simulation.api.FaultMode;
import ch.hsg.simulation.event.EventLog;
import ch.hsg.simulation.event.EventType;
import ch.hsg.simulation.event.SimulationEvent;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.execution.ExecutionStatus;
import ch.hsg.simulation.execution.ScheduledChange;
import ch.hsg.simulation.execution.StateChange;
import ch.hsg.simulation.model.ComponentState;
import ch.hsg.simulation.model.ComponentView;
import ch.hsg.simulation.model.EntityView;
import ch.hsg.simulation.model.InvalidStateChangeException;
import ch.hsg.simulation.model.NotFoundException;
import ch.hsg.simulation.model.Simulation;
import ch.hsg.simulation.model.SimulationException;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.model.SimulationStateException;
import ch.hsg.simulation.model.SimulationStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Executes exactly one {@link Simulation}. All public operations run on a single-threaded loop,
 * so commands for one simulation are processed sequentially and deterministically. The engine is
 * the only authority that advances simulated time and commits state changes.
 *
 * <p>Timed actions: a component returns phases with offsets; the engine schedules each phase at
 * {@code now + offset} and commits it when time reaches it (via {@link #step()},
 * {@link #runUntil(Duration)} or a {@link RealTimeDriver}). The action completes with its last phase.
 */
public final class SimulationEngine implements AutoCloseable {

    private Simulation simulation;
    private final SimulationClock clock = new SimulationClock();
    private final EventQueue eventQueue = new EventQueue();
    private final EventLog eventLog = new EventLog();
    private final SimulationContext context = new EngineContext();

    private final Map<String, Set<String>> activeFaults = new HashMap<>();
    private final Map<String, Integer> pendingActions = new HashMap<>();
    private final Map<UUID, CompletableFuture<SimulationEvent>> completions = new ConcurrentHashMap<>();

    private final ExecutorService commandExecutor;
    private volatile Thread loopThread;

    public SimulationEngine(Simulation simulation) {
        this.simulation = prepare(simulation);
        this.commandExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "simulation-" + simulation.getId());
            thread.setDaemon(true);
            loopThread = thread;
            return thread;
        });
    }

    // ---- lifecycle ----

    /** CREATED/STOPPED → RUNNING. Starting does not trigger any physical action. */
    public void start() {
        call(() -> {
            SimulationStatus status = simulation.getStatus();
            if (status != SimulationStatus.CREATED && status != SimulationStatus.STOPPED) {
                throw new SimulationStateException("Cannot start simulation in status " + status);
            }
            simulation.setStatus(SimulationStatus.RUNNING);
            publish(SimulationEvent.draft(EventType.SIMULATION_STARTED, null, Map.of()), null);
            return null;
        });
    }

    /** RUNNING → STOPPED. Acts as a pause: scheduled phases stay queued and continue after {@link #start()}. */
    public void stop() {
        call(() -> {
            requireRunning();
            simulation.setStatus(SimulationStatus.STOPPED);
            publish(SimulationEvent.draft(EventType.SIMULATION_STOPPED, null, Map.of()), null);
            return null;
        });
    }

    /**
     * Replaces the model with a freshly created one (same id) and clears time, queue, faults and
     * history. Listeners stay registered. Pending completions are cancelled.
     */
    public void reset(Simulation fresh) {
        call(() -> {
            if (!fresh.getId().equals(simulation.getId())) {
                throw new IllegalArgumentException("Reset must keep the simulation id");
            }
            simulation = prepare(fresh);
            clock.reset();
            eventQueue.clear();
            activeFaults.clear();
            pendingActions.clear();
            completions.values().forEach(future -> future.cancel(false));
            completions.clear();
            eventLog.clear();
            publish(SimulationEvent.draft(EventType.SIMULATION_RESET, null, Map.of()), null);
            return null;
        });
    }

    // ---- execution ----

    /**
     * Validates and executes an action. Unknown components raise {@link NotFoundException}; an
     * unsupported capability, invalid parameters or a component refusal return a REJECTED result
     * without changing state. Phases at offset zero are committed before this method returns.
     */
    public ComponentExecutionResult execute(String componentId, ComponentAction action) {
        Objects.requireNonNull(action, "action");
        return call(() -> doExecute(componentId, action));
    }

    /** Completes with the ACTION_COMPLETED event of an accepted action. */
    public CompletableFuture<SimulationEvent> completionOf(UUID commandId) {
        CompletableFuture<SimulationEvent> future = completions.get(commandId);
        if (future == null) {
            throw new NotFoundException("No executed action with command id " + commandId);
        }
        return future;
    }

    /** Commits the next scheduled phase, advancing the clock to its time. Returns false if nothing is scheduled. */
    public boolean step() {
        return call(() -> {
            requireRunning();
            Optional<EventQueue.ScheduledEntry> next = eventQueue.poll();
            next.ifPresent(this::commit);
            return next.isPresent();
        });
    }

    /** Commits all phases due up to {@code target} and advances the clock to it (never backwards). */
    public void runUntil(Duration target) {
        Objects.requireNonNull(target, "target");
        call(() -> {
            requireRunning();
            processDueUntil(target);
            return null;
        });
    }

    /** Commits all scheduled phases, advancing time as far as needed. */
    public void runUntilIdle() {
        call(() -> {
            requireRunning();
            while (!eventQueue.isEmpty()) {
                commit(eventQueue.poll().orElseThrow());
            }
            return null;
        });
    }

    // ---- faults ----

    public void setFault(String componentId, String faultId, boolean active) {
        call(() -> {
            Component component = requireComponent(componentId);
            boolean declared = component.getFaultModes().stream().map(FaultMode::id).anyMatch(faultId::equals);
            if (!declared) {
                throw new NotFoundException("Component " + componentId + " has no fault mode " + faultId);
            }
            Set<String> faults = activeFaults.computeIfAbsent(componentId, key -> new HashSet<>());
            boolean changed = active ? faults.add(faultId) : faults.remove(faultId);
            if (changed) {
                publish(SimulationEvent.draft(active ? EventType.FAULT_ACTIVATED : EventType.FAULT_CLEARED,
                        componentId, Map.of("fault", faultId)), null);
                observeComponents();
            }
            return null;
        });
    }

    // ---- observation ----

    public SimulationSnapshot snapshot() {
        return call(this::buildSnapshot);
    }

    public SimulationStatus getStatus() {
        return call(simulation::getStatus);
    }

    public Duration now() {
        return call(clock::now);
    }

    public UUID getSimulationId() {
        return simulation.getId();
    }

    /** Full committed event history (trace). */
    public List<SimulationEvent> events() {
        return eventLog.events();
    }

    /** Listeners are called on the engine loop after each commit; they must not block or call back into the engine synchronously. */
    public void addListener(Consumer<SimulationEvent> listener) {
        eventLog.addListener(listener);
    }

    public void removeListener(Consumer<SimulationEvent> listener) {
        eventLog.removeListener(listener);
    }

    @Override
    public void close() {
        completions.values().forEach(future -> future.cancel(false));
        commandExecutor.shutdownNow();
    }

    // ---- internals (engine loop only) ----

    private ComponentExecutionResult doExecute(String componentId, ComponentAction action) {
        requireRunning();
        Component component = requireComponent(componentId);

        Optional<Capability> capability = component.findCapability(action.capability());
        Optional<String> problem = capability.isEmpty()
                ? Optional.of("Component " + componentId + " does not support capability '" + action.capability() + "'")
                : capability.get().validate(action);
        if (problem.isPresent()) {
            return reject(componentId, action, ComponentExecutionResult.rejected(problem.get()));
        }

        ComponentExecutionResult result;
        try {
            result = Objects.requireNonNull(component.execute(action, context), "Component returned no result");
        } catch (RuntimeException exception) {
            result = ComponentExecutionResult.failed(componentId + " failed: " + exception.getMessage());
        }
        if (!result.isExecuted()) {
            return reject(componentId, action, result);
        }

        completions.put(action.commandId(), new CompletableFuture<>());
        pendingActions.merge(componentId, 1, Integer::sum);
        publish(SimulationEvent.draft(EventType.ACTION_ACCEPTED, componentId, actionPayload(action, result)),
                action.commandId());

        Duration start = clock.now();
        List<ScheduledChange> phases = result.phases();
        for (int index = 0; index < phases.size(); index++) {
            ScheduledChange phase = phases.get(index);
            eventQueue.schedule(start, start.plus(phase.offset()), action.commandId(), componentId,
                    phase.changes(), phase.events(), index == phases.size() - 1);
        }
        processDueUntil(start);
        return result;
    }

    private ComponentExecutionResult reject(String componentId, ComponentAction action, ComponentExecutionResult result) {
        EventType type = result.status() == ExecutionStatus.FAILED
                ? EventType.ACTION_FAILED : EventType.ACTION_REJECTED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capability", action.capability());
        payload.put("reason", result.errorMessage().orElse(""));
        publish(SimulationEvent.draft(type, componentId, payload), action.commandId());
        return result;
    }

    private void processDueUntil(Duration target) {
        while (eventQueue.peek().filter(entry -> entry.time().compareTo(target) <= 0).isPresent()) {
            commit(eventQueue.poll().orElseThrow());
        }
        if (target.compareTo(clock.now()) > 0) {
            clock.advanceTo(target);
        }
    }

    private void commit(EventQueue.ScheduledEntry entry) {
        clock.advanceTo(entry.time());
        try {
            List<SimulationEvent> applied = simulation.apply(entry.changes());
            applied.forEach(event -> publish(event, entry.commandId()));
            entry.events().forEach(event -> publish(event, entry.commandId()));
        } catch (InvalidStateChangeException exception) {
            publish(SimulationEvent.draft(EventType.CHANGE_REJECTED, entry.componentId(),
                    Map.of("reason", exception.getMessage())), entry.commandId());
        }
        if (entry.completesAction()) {
            pendingActions.computeIfPresent(entry.componentId(), (key, count) -> count > 1 ? count - 1 : null);
        }
        observeComponents();
        if (entry.completesAction()) {
            SimulationEvent completed = publish(
                    SimulationEvent.draft(EventType.ACTION_COMPLETED, entry.componentId(), Map.of()), entry.commandId());
            CompletableFuture<SimulationEvent> future = completions.get(entry.commandId());
            if (future != null) {
                future.complete(completed);
            }
        }
    }

    /** One pass over all components so sensors can update their observed state. */
    private void observeComponents() {
        for (Component component : simulation.getComponents()) {
            Optional<ComponentState> observed;
            try {
                observed = component.observe(context);
            } catch (RuntimeException exception) {
                continue;
            }
            if (observed.isPresent() && !observed.get().equals(simulation.stateOf(component.getId()))) {
                simulation.apply(List.of(StateChange.componentState(component.getId(), observed.get())))
                        .forEach(event -> publish(event, null));
            }
        }
    }

    private SimulationEvent publish(SimulationEvent draft, UUID commandId) {
        SimulationEvent event = draft.stamped(clock.now(), commandId);
        eventLog.append(event);
        return event;
    }

    private SimulationSnapshot buildSnapshot() {
        List<ComponentView> components = new ArrayList<>();
        for (Component component : simulation.getComponents()) {
            components.add(new ComponentView(
                    component.getId(),
                    component.getType(),
                    simulation.stateOf(component.getId()),
                    component.getCapabilities().stream().map(Capability::name).collect(Collectors.toSet()),
                    component.getFaultModes().stream().map(FaultMode::id).collect(Collectors.toSet()),
                    activeFaults.getOrDefault(component.getId(), Set.of()),
                    pendingActions.containsKey(component.getId())));
        }
        return new SimulationSnapshot(simulation.getId(), simulation.getDefinitionId(), simulation.getName(),
                simulation.getStatus(), clock.now(), components, simulation.getEntities());
    }

    private Component requireComponent(String componentId) {
        return simulation.findComponent(componentId)
                .orElseThrow(() -> new NotFoundException("Unknown component: " + componentId));
    }

    private void requireRunning() {
        if (simulation.getStatus() != SimulationStatus.RUNNING) {
            throw new SimulationStateException("Simulation " + simulation.getId() + " is not running");
        }
    }

    private static Simulation prepare(Simulation simulation) {
        simulation.validate();
        simulation.seal();
        return simulation;
    }

    private static Map<String, Object> actionPayload(ComponentAction action, ComponentExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capability", action.capability());
        payload.put("parameters", action.parameters());
        payload.put("duration", result.duration().toString());
        return payload;
    }

    private <T> T call(Callable<T> task) {
        if (Thread.currentThread() == loopThread) {
            return invokeDirectly(task);
        }
        try {
            return commandExecutor.submit(task).get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SimulationException("Simulation command failed", exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SimulationException("Interrupted while waiting for the simulation", exception);
        } catch (RejectedExecutionException exception) {
            throw new SimulationStateException("Simulation " + simulation.getId() + " is closed");
        }
    }

    private static <T> T invokeDirectly(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SimulationException("Simulation command failed", exception);
        }
    }

    private final class EngineContext implements SimulationContext {

        @Override
        public Duration now() {
            return clock.now();
        }

        @Override
        public Optional<EntityView> findEntity(String entityId) {
            return simulation.findEntity(entityId);
        }

        @Override
        public List<EntityView> entitiesAt(String componentId) {
            return simulation.entitiesAt(componentId);
        }

        @Override
        public boolean isConnected(String sourceId, String targetId) {
            return simulation.isConnected(sourceId, targetId);
        }

        @Override
        public ComponentState stateOf(String componentId) {
            return simulation.stateOf(componentId);
        }

        @Override
        public boolean isBusy(String componentId) {
            return pendingActions.containsKey(componentId);
        }

        @Override
        public boolean isFaultActive(String componentId, String faultId) {
            return activeFaults.getOrDefault(componentId, Set.of()).contains(faultId);
        }
    }
}
