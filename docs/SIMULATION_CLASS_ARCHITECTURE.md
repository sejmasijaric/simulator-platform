# Simulation Platform – Detailed Class Architecture

## 1. Scope and principles

This design refines the two supplied diagrams. Java implementations define physical layouts and components; there is **no configuration-driven layout builder**. The generic core knows neither Fischertechnik nor Dobot classes. REST and MQTT are adapters, not core dependencies. Camunda/application business logic remains outside the simulation core. A simulation is one isolated runtime instance, not a shared global model.

### Architectural invariants

- `SimulationDefinition.create()` creates a **fresh** simulation instance with independent component and entity state each time.
- `SimulationManager` owns a registry of simulation runtimes; `SimulationEngine` owns execution of exactly one `Simulation`.
- `Simulation` owns components and entities. `SimulationEngine` owns the clock and event queue. The manager owns engines.
- The engine is the only authority allowed to advance simulated time and commit state-changing actions.
- Components do not call REST/MQTT directly and do not advance the clock.
- One simulation processes commands/events sequentially for deterministic execution; different simulations can run concurrently.
- Time is simulation time, not wall-clock time. Any real-time pacing is an optional outer adapter.
- Entity location and component state are authoritative within a simulation; event payloads are notifications, not a second source of truth.

### Revision 2 (2026-10-28)

Overrides the sections below where they differ. Rationale: [adr/](adr/README.md).

1. Time: logical clock in the core; `RealTimeDriver` advances it with the wall clock for application use. Adapters wait on `SimulationEngine.completionOf(commandId)`.
2. `ComponentExecutionResult` holds `List<ScheduledChange(offset, changes, events)>` instead of `duration` + `changes`. Actions are event-scheduled (§8.1).
3. All changes, including a component's own state, are `StateChange`s. `Simulation` owns component state. `Component.getState()` is replaced by `initialState()`.
4. `Entity` has `attributes` instead of `EntityState`. Components have `getCapacity()`. Connections are directed.
5. Faults: core provides `FaultMode`, `Component.getFaultModes()`, `SimulationEngine.setFault(...)`, `SimulationContext.isFaultActive(...)`. The layout component implements the effect.
6. Sensors override `Component.observe(context)`; the engine calls it after each commit.
7. `SimulationContext.isBusy(id)` is true while an accepted action of the component is running. The component decides whether to reject.
8. Event log is required: `SimulationEngine.events()`.
9. `SimulationDefinition.create(UUID simulationId)`; the manager assigns ids. `stop` pauses, `reset` rebuilds with the same id (§8.4).
10. The generic REST API (§7) is for control and tests. Applications use a layout-specific hardware-compatible adapter.

## 2. Package organization

```text
simulation/
  api/              SimulationDefinition, Component, Capability
  model/            Simulation, Entity, ComponentState, SimulationStatus
  runtime/          SimulationManager, SimulationEngine, SimulationClock,
                    SimulationContext, EventQueue
  execution/        ComponentAction, ComponentExecutionResult, ExecutionStatus
  event/            SimulationEvent, EventType
  adapters/rest/    REST controllers, DTOs, exception mapping
  adapters/mqtt/    MQTT publisher and subscription bridge
  systems/fischertechnik/  Definition and concrete components
  systems/dobot/          Definition and concrete components
```

## 3. Class and interface contracts

### `SimulationDefinition` (interface, api)
**Responsibility:** Discoverable Java-coded blueprint/factory for a particular physical system. It does not store mutable runtime state.

- `String getId()` – stable unique definition ID, e.g. `fischertechnik-factory-4`.
- `String getName()` – display name.
- `String getDescription()` – brief system description.
- `Optional<URI> getLayoutImage()` – optional UI resource; not essential for execution.
- `Simulation create()` – constructs a new, initially CREATED simulation with component instances, connections and initial entities.

**Rules:** definitions are registered at application startup, e.g. via dependency injection or `ServiceLoader`; no JSON/YAML definition format required. Validate IDs and component references when creating a simulation.

### `SimulationManager` (class, runtime)
**Responsibility:** Discover definitions; create, look up, start, stop and remove independent simulation instances. Delegates execution to engines.

**Fields**
- `Map<String, SimulationDefinition> definitions` – registered immutable definitions.
- `ConcurrentMap<UUID, SimulationEngine> engines` – runtime registry keyed by simulation ID.

**Public methods**
- `List<SimulationDefinition> getDefinitions()`
- `UUID create(String definitionId)` – invokes definition factory, validates model, creates engine and registers it.
- `SimulationSnapshot getSimulation(UUID simulationId)` – read-only external view.
- `List<SimulationSnapshot> getSimulations()`
- `void start(UUID simulationId)`
- `void stop(UUID simulationId)`
- `ComponentExecutionResult execute(UUID simulationId, String componentId, ComponentAction action)` – optional convenient command delegation.
- `void remove(UUID simulationId)` – only when stopped; releases resources.

**Failure behavior:** unknown definition/simulation → not-found exception; invalid lifecycle transition → state exception. Never expose mutable `Simulation` through public adapters.

### `Simulation` (class, model)
**Responsibility:** Own one simulation's model and current state. It does not schedule commands or run its own thread.

**Fields**
- `UUID id`
- `String definitionId`
- `String name`
- `SimulationStatus status` (`CREATED`, `RUNNING`, `STOPPED`, `FAILED`)
- `Map<String, Component> components`
- `Map<String, Entity> entities`

**Methods**
- `Optional<Component> findComponent(String id)`
- `Optional<Entity> findEntity(String id)`
- `Collection<Component> getComponents()` – unmodifiable view
- `Collection<Entity> getEntities()` – unmodifiable view
- package-scoped `addComponent(Component)` / `addEntity(Entity)` for definition assembly
- package-scoped `setStatus(SimulationStatus)` for lifecycle control
- `SimulationSnapshot snapshot()` – immutable snapshot, including component/entity states

**Constraints:** component IDs and entity IDs unique per simulation. Components belong to one simulation instance; `0..*` components is structurally possible, but validation may require `1..*` for runnable physical layouts. Entities may be absent initially.

### `SimulationEngine` (class, runtime)
**Responsibility:** Serialize actions, invoke components, advance simulated time, apply resulting state changes, enqueue/dispatch events and manage lifecycle.

**Fields**
- `Simulation simulation`
- `SimulationClock clock`
- `EventQueue eventQueue`
- `SimulationContext context`
- `ExecutorService commandExecutor` – single-threaded per simulation; or equivalent serialized command loop

**Methods**
- `void start()` – CREATED/STOPPED → RUNNING; no automatic physical actions implied.
- `void stop()` – RUNNING → STOPPED, stop accepting new actions; define whether queued events remain for restart.
- `ComponentExecutionResult execute(String componentId, ComponentAction action)` – validate state, dispatch action, process result and events.
- `void step()` – process the next scheduled event, if any; advance clock to event timestamp.
- `void runUntil(Duration simulationTime)` – optional, process due events until target time.
- `void handle(SimulationEvent event)` – apply/distribute supported event types.
- `SimulationSnapshot snapshot()`

**Key design decision:** define action-time semantics explicitly. Recommended initial implementation: execute an action at current simulation time; return `duration` and schedule its completion for `now + duration`. Do **not** immediately advance the clock by duration when multiple operations may overlap. `step()` moves to the next scheduled event and commits completion. For simpler first milestone, synchronous atomic actions are possible, but document that parallel activity is not modeled.

### `SimulationClock` (class, runtime)
**Responsibility:** Maintain monotonic simulated time.

**Fields**
- `Duration currentTime = Duration.ZERO`

**Methods**
- `Duration now()`
- `void advance(Duration delta)` – reject negative delta.
- `void advanceTo(Duration target)` – reject target earlier than current time.
- `void reset()` – allowed only as part of a reset of the entire simulation state, not while active.

**Important:** `SimulationClock` is a logical clock. No `Thread.sleep()` and no dependency on system time.

### `Component` (interface, api)
**Responsibility:** Abstract an executable simulated hardware component with explicit capabilities.

**Methods**
- `String getId()`
- `String getType()`
- `ComponentState getState()` – immutable state snapshot
- `Set<Capability> getCapabilities()`
- `ComponentExecutionResult execute(ComponentAction action, SimulationContext context)`

**Design:** prefer a typed action object rather than `execute(String capability)`. Components validate capabilities and parameters. A component may refer to connected component IDs, but should not directly own another component's lifecycle or mutate its state. Connections are defined by the Java implementation and may be represented by a simple `Map<String, Set<String>>` in `Simulation` or a dedicated graph only if routing requires it.

### `Capability` (record or immutable value object, api)
**Responsibility:** Describe a supported operation independently of REST/MQTT naming.

**Fields**
- `String name` – e.g. `move`, `start`, `stop`, `detect`
- `Map<String, ParameterType> parameters` – expected parameter names/types

**Methods**
- `boolean supports(ComponentAction action)`

**Note:** capabilities are descriptive; physical semantics live in concrete components.

### `ComponentAction` (record, execution)
**Responsibility:** Immutable request to execute one component capability.

**Fields**
- `String capability`
- `Map<String, Object> parameters` – validate and copy defensively
- `UUID commandId` – optional idempotency/trace correlation

### `ComponentState` (interface or immutable record, model)
**Responsibility:** Return an observable, serializable component state.

**Minimal first version:** `record ComponentState(Map<String, Object> attributes)`, with immutable copied attributes and documented per-component schemas. For stricter typing later, define specialized records (`ConveyorState`, `RobotState`) implementing a marker interface. Avoid using unvalidated arbitrary maps inside the component implementation.

### `Entity` (class, model)
**Responsibility:** Represent a workpiece or other movable item tracked within a simulation.

**Fields**
- `String id`
- `String type`
- `EntityState state` – typed enum or immutable domain state
- `String locationComponentId` – nullable/optional when outside the modeled system

**Methods**
- `String getId()` / `String getType()`
- `EntityState getState()`
- `Optional<String> getLocationComponentId()`
- package/internal `moveTo(String componentId)` – validated by simulation context
- package/internal `updateState(EntityState state)`

**Invariant:** the location must reference a component in the same simulation, or be absent. If the factory needs exact coordinates or slots, introduce a `Location` value object rather than overloading component IDs.

### `SimulationContext` (interface, runtime)
**Responsibility:** Expose a narrow, simulation-scoped capability to component implementations without exposing engine internals.

**Methods**
- `Duration now()`
- `Optional<EntityView> findEntity(String entityId)`
- `List<EntityView> entitiesAt(String componentId)`
- `boolean isConnected(String sourceId, String targetId)`

**Mutation boundary:** components should return requested changes in `ComponentExecutionResult` rather than directly changing foreign entities through context. The engine validates and commits them. This prevents partial mutation when an action fails.

### `ComponentExecutionResult` (immutable record, execution)
**Responsibility:** Report the outcome of a component action without managing execution itself.

**Fields**
- `ExecutionStatus status` (`ACCEPTED`, `REJECTED`, `COMPLETED`, `FAILED`)
- `Duration duration` – nonnegative simulated execution duration
- `List<SimulationEvent> events` – events to publish/schedule
- `List<StateChange> changes` – proposed state changes, validated by engine
- `Optional<String> errorMessage`

**Factories:** `accepted(...)`, `rejected(...)`, `completed(...)`, `failed(...)`. `success: boolean` alone is insufficient to distinguish accepted from completed asynchronous operations.

### `SimulationEvent` (interface or sealed hierarchy, event)
**Responsibility:** Represent events in the simulation domain, not just sensor observations.

**Common fields**
- `UUID id`
- `Duration timestamp`
- `String sourceComponentId`
- `EventType type`
- `Map<String, Object> payload`

**Examples:** `SensorTriggered`, `ActionCompleted`, `EntityMoved`, `ComponentStateChanged`, `SimulationFailed`. Keep sensor events as one concrete subtype if useful.

### `EventQueue` (class, runtime)
**Responsibility:** Hold pending events ordered by `(timestamp, insertionSequence)` for deterministic tie-breaking.

**Fields**
- `PriorityQueue<ScheduledEvent> events`
- `long nextSequence`

**Methods**
- `void schedule(SimulationEvent event)` – reject events in the past
- `Optional<SimulationEvent> peek()`
- `Optional<SimulationEvent> poll()`
- `boolean isEmpty()`
- `void clear()`

**Important:** schedule and drain on the simulation's serialized execution loop. Do not use a concurrently mutated priority queue without coordination.

## 4. Concrete system implementations

`FischertechnikDefinition implements SimulationDefinition` creates e.g. `FischertechnikConveyor implements Component`, `FischertechnikSensor implements Component`, `FischertechnikRobot implements Component`, plus entities and hardcoded connections. `DobotConveyorDefinition implements SimulationDefinition` creates `DobotRobot implements Component` and `DobotConveyor implements Component` with its own layout. These classes encode physical behavior and state transitions; they do not extend `SimulationEngine` or `SimulationManager`.

Start with one component of each behavior category (actuator, sensor, transport). Keep concrete methods private except interface methods and focused domain helpers.

## 5. UML relationship decisions

| From | To | UML relation | Cardinality / meaning |
|---|---|---|---|
| SimulationManager | SimulationDefinition | dependency | discovers/uses `0..*` registered definitions |
| SimulationManager | SimulationEngine | composition | manager owns `0..*` engines |
| SimulationEngine | Simulation | composition | exactly `1` simulation per engine |
| SimulationEngine | SimulationClock | composition | exactly `1` clock per engine |
| SimulationEngine | EventQueue | composition | exactly `1` queue per engine |
| SimulationEngine | SimulationContext | association | one runtime context |
| SimulationDefinition | Simulation | dependency «create» | factory creates new instances |
| Simulation | Component | composition | `1..*` components in valid runnable system |
| Simulation | Entity | composition | `0..*` entities |
| Component | Capability | association | `0..*` capabilities |
| Component | ComponentExecutionResult | dependency | returns result |
| ComponentExecutionResult | SimulationEvent | association | `0..*` emitted events |
| ComponentExecutionResult | StateChange | association | `0..*` requested changes |
| ConcreteDefinition | SimulationDefinition | realization | implements interface |
| ConcreteComponent | Component | realization | implements interface |

**Correction to original UML:** the black diamond belongs at the owning end. If manager owns engine, diamond at manager; if engine owns simulation and clock, diamond at engine. The original direct `SimulationManager` → `SimulationDefinition` dashed dependency is appropriate. A `connectedTo` relationship between components is an association, not composition, and should be optional/many-to-many if kept in the class diagram.

## 6. Sequence: create and execute

1. Web UI or application calls `POST /simulations` with `definitionId`.
2. REST adapter calls `SimulationManager.create(definitionId)`.
3. Manager invokes `SimulationDefinition.create()`, producing fresh components, entities and connections.
4. Manager validates the model, constructs engine/clock/queue/context and stores the engine.
5. Client starts simulation via manager; engine marks it RUNNING.
6. Client invokes a capability through REST; adapter maps request to `ComponentAction`.
7. Manager delegates to engine's serialized command executor.
8. Engine validates simulation status, component ID and supported capability.
9. Engine calls `Component.execute(action, context)`.
10. Engine validates returned changes/events. For an instantaneous action it commits changes and emits events; for a timed action it schedules a completion at `now + duration`.
11. On `step()`, engine advances clock to the next event and commits scheduled completion/state changes.
12. MQTT adapter publishes committed events; UI queries immutable snapshots. MQTT failures do not roll back simulation state.

## 7. API boundaries (proposed)

- `GET /simulation-definitions` – list available Java-coded layouts.
- `POST /simulations` – create instance by definition ID.
- `GET /simulations` and `GET /simulations/{id}` – inspect.
- `POST /simulations/{id}/start` and `/stop` – lifecycle.
- `POST /simulations/{id}/components/{componentId}/actions` – execute action.
- `POST /simulations/{id}/step` – advance to next scheduled event.
- `GET /simulations/{id}/events` – optional trace/history.

Use adapters to map HTTP status and JSON DTOs to domain commands. MQTT topic names and payload contracts should mimic the corresponding real hardware interface where hardware/application compatibility is required; they are not defined by the generic component interface.

## 8. Open decisions to agree on before coding

1. **Execution semantics:** atomic/synchronous MVP vs event-scheduled durations (recommended if overlapping physical actions matter).
2. **State transition mechanism:** immutable `StateChange` proposals vs components mutating only their own state; prefer proposed changes for transactional consistency.
3. **Connection model:** simple hardcoded adjacency map vs typed ports and transport routes; start simple unless layout demands routing semantics.
4. **Reset/restart:** does `stop` pause and resume or terminate? Recommended: `stop` pauses; `reset` reconstructs from definition.
5. **Event delivery:** publish events after successful state commit; define sensor payloads and hardware-compatible MQTT topics separately.
6. **Determinism:** define command ordering, equal-timestamp ordering, and whether random behavior uses seeded RNG.
7. **Error handling:** distinguish validation errors, component failures and runtime failures; never silently ignore unsupported capabilities.

## 9. Suggested implementation order

**Milestone 1 – Model and contracts:** `SimulationDefinition`, `Component`, `Capability`, `ComponentAction`, `ComponentState`, `Entity`, `Simulation`, validation and unit tests.

**Milestone 2 – Deterministic runtime:** `SimulationClock`, `SimulationEvent`, `EventQueue`, `ComponentExecutionResult`, `SimulationContext`, `SimulationEngine`; test action execution, event ordering, time and failure cases.

**Milestone 3 – Lifecycle:** `SimulationManager`, independent simulation instances, start/stop, snapshots, concurrent client calls.

**Milestone 4 – One vertical slice:** one Java-coded Dobot/conveyor or Fischertechnik definition; one command → simulated state change → event → observable snapshot.

**Milestone 5 – Adapters and compatibility:** REST/MQTT mapping, contract tests against the real hardware's observable API, and a second physical layout proving core reuse.

## 10. Acceptance tests

- Creating the same definition twice returns different IDs and independent component/entity state.
- Unsupported action and invalid parameters fail predictably without changing state.
- Simulated time never goes backwards; equal-timestamp events have reproducible order.
- An action's entity move cannot target an unknown component or invalid connection.
- Concurrent commands for one simulation are serialized; commands for separate simulations remain independent.
- Failure in MQTT delivery does not corrupt or reverse committed simulation state.
- Application logic can switch between simulator and corresponding physical interface without changing application business logic, subject to contract tests.
- Adding a new concrete component/definition requires no modification to generic runtime classes.
