# Architecture

Dashed elements are planned, all others are implemented. Decisions: [ADRs](adr/README.md). Class contracts: [SIMULATION_CLASS_ARCHITECTURE.md](SIMULATION_CLASS_ARCHITECTURE.md).

## 1. Building blocks

```mermaid
flowchart TB
    subgraph external["External"]
        ui["Web UI"]
        app["Application"]
    end

    subgraph adapters["Adapters"]
        rest["REST control API"]
        hw["Hardware-compatible adapter<br/>(per layout)"]
        driver["RealTimeDriver"]
    end

    subgraph core["Core"]
        manager["SimulationManager"]
        engine["SimulationEngine"]
        clock["SimulationClock"]
        queue["EventQueue"]
        log["EventLog"]
        sim["Simulation"]
        apiNode["api: SimulationDefinition,<br/>Component, Capability, FaultMode"]
    end

    subgraph layouts["Layouts"]
        test["Test layout"]
        ft["Fischertechnik Factory 4.0"]
        dobot["Dobot + conveyor"]
    end

    physical[("Physical layout")]

    ui --> rest
    app --> hw
    app -. "same interface" .-> physical
    rest --> manager
    hw --> manager
    driver --> engine
    manager --> engine
    engine --> clock
    engine --> queue
    engine --> log
    engine --> sim
    sim --> apiNode
    test -- implements --> apiNode
    ft -- implements --> apiNode
    dobot -- implements --> apiNode
    log -. listeners .-> hw

    classDef planned stroke-dasharray: 5 5
    class ui,hw,ft,dobot planned
```

## 2. Core classes

```mermaid
classDiagram
    direction LR

    class SimulationManager {
        +create(definitionId) UUID
        +start(id)
        +stop(id)
        +reset(id)
        +remove(id)
        +execute(id, componentId, action) ComponentExecutionResult
        +setFault(id, componentId, faultId, active)
        +getSimulation(id) SimulationSnapshot
        +engine(id) SimulationEngine
    }
    class SimulationEngine {
        +execute(componentId, action) ComponentExecutionResult
        +completionOf(commandId) CompletableFuture
        +step() boolean
        +runUntil(time)
        +runUntilIdle()
        +setFault(componentId, faultId, active)
        +snapshot() SimulationSnapshot
        +events() List~SimulationEvent~
        +addListener(listener)
    }
    class SimulationClock {
        +now() Duration
        +advanceTo(time)
    }
    class EventQueue {
        +schedule(now, time, ...) ScheduledEntry
        +poll() ScheduledEntry
    }
    class EventLog {
        +append(event)
        +events() List~SimulationEvent~
    }
    class RealTimeDriver {
        +start()
        +close()
    }
    class Simulation {
        +addComponent(component)
        +addEntity(entity)
        +connect(from, to)
        +validate()
        +apply(changes) List~SimulationEvent~
        +stateOf(componentId) ComponentState
        +entitiesAt(componentId) List~EntityView~
    }
    class SimulationDefinition {
        <<interface>>
        +getId()
        +create(simulationId) Simulation
    }
    class Component {
        <<interface>>
        +getCapabilities() Set~Capability~
        +getFaultModes() Set~FaultMode~
        +getCapacity() int
        +initialState() ComponentState
        +execute(action, context) ComponentExecutionResult
        +observe(context) Optional~ComponentState~
    }
    class SimulationContext {
        <<interface>>
        +now()
        +entitiesAt(componentId)
        +stateOf(componentId)
        +isBusy(componentId)
        +isFaultActive(componentId, faultId)
    }
    class ComponentExecutionResult {
        +status: ExecutionStatus
        +phases: List~ScheduledChange~
        +duration() Duration
    }
    class ScheduledChange {
        +offset: Duration
        +changes: List~StateChange~
        +events: List~SimulationEvent~
    }
    class StateChange {
        <<sealed>>
        CreateEntity
        RemoveEntity
        MoveEntity
        UpdateEntityAttributes
        UpdateComponentState
    }
    class Entity {
        +id
        +type
        +attributes
        +locationComponentId
    }
    class Capability {
        +name
        +parameters
    }
    class FaultMode {
        +id
        +description
    }

    SimulationManager ..> SimulationDefinition : uses
    SimulationManager *-- "0..*" SimulationEngine
    SimulationEngine *-- "1" Simulation
    SimulationEngine *-- SimulationClock
    SimulationEngine *-- EventQueue
    SimulationEngine *-- EventLog
    SimulationEngine ..> SimulationContext : provides
    RealTimeDriver --> SimulationEngine : advances
    SimulationDefinition ..> Simulation : creates
    Simulation *-- "1..*" Component
    Simulation *-- "0..*" Entity
    Component --> "0..*" Capability
    Component --> "0..*" FaultMode
    Component ..> ComponentExecutionResult : returns
    ComponentExecutionResult *-- "0..*" ScheduledChange
    ScheduledChange --> "0..*" StateChange
```

## 3. Timed action

Example: transport with phases at 0 s (busy), 2 s (pick up) and 4 s (drop off, idle). The hardware adapter is planned; in tests, `step()` or `runUntil()` replaces the `RealTimeDriver`.

```mermaid
sequenceDiagram
    autonumber
    participant App as Application
    participant HW as Hardware adapter (planned)
    participant Eng as SimulationEngine
    participant Comp as Component
    participant Sim as Simulation
    participant Drv as RealTimeDriver

    App->>HW: command
    HW->>Eng: execute(componentId, action)
    Eng->>Eng: validate status, component, capability, parameters
    Eng->>Comp: execute(action, context)
    Comp-->>Eng: ACCEPTED, phases at 0 s, 2 s, 4 s
    Eng->>Eng: schedule phases, log ACTION_ACCEPTED
    Eng->>Sim: apply(phase 0 s)
    Eng-->>HW: result
    HW->>Eng: completionOf(commandId)

    loop every tick
        Drv->>Eng: runUntil(t)
        Eng->>Sim: apply(due phases)
        Eng->>Comp: observe(context) on all components
        Eng->>Eng: log changes
    end

    Eng-->>HW: ACTION_COMPLETED at 4 s
    HW-->>App: response
```

## 4. Fault

```mermaid
sequenceDiagram
    autonumber
    participant Tester
    participant Eng as SimulationEngine
    participant Comp as Transporter
    participant App as Application

    Tester->>Eng: setFault("T", "ITEM_NOT_MOVED", true)
    Eng->>Eng: log FAULT_ACTIVATED
    App->>Eng: execute("T", transport IN to OUT)
    Eng->>Comp: execute(action, context)
    Comp-->>Eng: same phases, without moves
    Eng-->>App: action completes after 4 s
    App->>Eng: read status: item still at IN
```

## 5. Lifecycle

```mermaid
stateDiagram-v2
    [*] --> CREATED : create
    CREATED --> RUNNING : start
    RUNNING --> STOPPED : stop
    STOPPED --> RUNNING : start
    RUNNING --> CREATED : reset
    STOPPED --> CREATED : reset
    CREATED --> CREATED : reset
    CREATED --> [*] : remove
    STOPPED --> [*] : remove
```

Only `RUNNING` accepts commands and advances time. `stop` keeps scheduled phases. `reset` rebuilds the simulation from its definition with the same id.
