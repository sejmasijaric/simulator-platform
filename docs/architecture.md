# Architecture

Decisions: [ADRs](adr/README.md). Layout format: [layouts/layout-format.md](layouts/layout-format.md).

## Hexagonal view

![Hexagonal architecture](diagrams/hexagonal-architecture.png)

Vector version: [hexagonal-architecture.svg](diagrams/hexagonal-architecture.svg). Classes: [class-diagram.svg](diagrams/class-diagram.svg).

## 1. Building blocks

```mermaid
flowchart TB
    subgraph external["Outside"]
        ui["Web UI"]
        app["Application<br/>(e.g. Camunda process)"]
        broker[("MQTT broker")]
        files[("layouts/*.yaml")]
    end

    subgraph adapters["Adapters"]
        rest["ControlController<br/>REST control API"]
        http["LayoutHttpFilter + LayoutHttpApi<br/>layout's own HTTP API"]
        loader["YamlLayoutLoader"]
        mqtt["MqttEventPublisher"]
    end

    subgraph ports["Ports (core.port)"]
        control["SimulationControl"]
        source["LayoutSource"]
        listener["SimulationListener"]
    end

    subgraph core["Core"]
        manager["SimulationManager"]
        engine["SimulationEngine<br/>marking · time · faults · log"]
        interpreter["NetInterpreter"]
        net["NetDefinition"]
    end

    ui --> rest --> control
    app --> http --> control
    control -. implemented by .-> manager
    manager --> engine --> interpreter --> net
    manager --> source
    loader -. implements .-> source
    loader --> files
    mqtt --> control
    mqtt --> broker
    engine -. events .-> listener
```

## 2. Core classes

```mermaid
classDiagram
    direction LR
    class SimulationControl {
        <<interface>>
        +create(layoutId) UUID
        +start(id) / stop(id) / reset(id)
        +command(id, place, parameters, returnsAfter, origin) CommandHandle
        +readSensors(id) Map
        +placeToken(id, place, tokenId, attributes)
        +setSensorFault(id, sensor, fault)
        +setNetFault(id, transition, fault)
        +setActiveModules(id, modules)
        +step(id) / runUntil(id, time) / setSpeed(id, factor)
        +snapshot(id) Snapshot
        +events(id, from)
    }
    class SimulationManager
    class SimulationEngine {
        -now Duration
        -marking Marking
        -queue EventQueue
        -faults Faults
        -log EventLog
    }
    class NetInterpreter {
        +next(marking, faults, modules) Firing
    }
    class NetDefinition {
        <<record>>
        places · transitions · sensors
        http · events · image · tokenTemplates
    }
    class Marking {
        +tokensAt(place)
        +add(place, token) / remove(tokenId)
    }
    class Token {
        <<record>>
        id · attributes · commandId
    }
    class Faults
    class RealTimeDriver

    SimulationManager ..|> SimulationControl
    SimulationManager *-- "0..*" SimulationEngine
    SimulationManager *-- "0..*" RealTimeDriver
    RealTimeDriver --> SimulationEngine : advances
    SimulationEngine *-- Marking
    SimulationEngine *-- Faults
    SimulationEngine *-- NetInterpreter
    NetInterpreter --> NetDefinition
    Marking o-- "0..*" Token
```

## 3. A command through the layout's own interface

Example: `GET /ov/burn?machine=ov_1` with the burn taking 5 s + 2 × 2.4 s.

```mermaid
sequenceDiagram
    autonumber
    participant App as Application
    participant Api as LayoutHttpApi
    participant Ctl as SimulationControl
    participant Eng as SimulationEngine
    participant Net as NetInterpreter
    participant Drv as RealTimeDriver

    App->>Api: GET /ov/burn?machine=ov_1
    Api->>Api: check parameters (machine, time default 2)
    Api->>Ctl: command(sim, cmd_ov_burn, {machine, time}, [ov_burn, ov_burn_empty])
    Ctl->>Eng: request token into cmd_ov_burn
    Eng->>Net: next firing?
    Net-->>Eng: ov_drive_in (takes request, ov_idle, workpiece)
    Eng-->>Api: CommandHandle
    loop every 20 ms
        Drv->>Eng: runUntil(t)
        Eng->>Net: next firing?
    end
    Note over Eng: 5 s: ov_drive_in ends, ov_burn starts<br/>14.8 s: ov_burn ends, workpiece back on oven, ov_idle back
    Eng-->>Api: COMMAND_COMPLETED (command id)
    Api-->>App: 200 {"attributes": [], "process_time": ..., ...}
```

A second burn sent meanwhile waits as a token in `cmd_ov_burn` until `ov_idle` has its token back, like the factory's queue.

## 4. A fault

```mermaid
sequenceDiagram
    autonumber
    participant UI as Web UI
    participant Ctl as SimulationControl
    participant App as Application
    participant Api as LayoutHttpApi

    UI->>Ctl: setNetFault(sim, ov_drive_in, BLOCKED)
    App->>Api: GET /ov/burn?machine=ov_1
    Note over Api: the request waits in cmd_ov_burn
    UI->>Ctl: setSensorFault(sim, ov_lb5, WRONG_VALUE)
    App->>Api: GET /ov/status_of_light_barrier?machine=ov_1&lb=5
    Api-->>App: interrupted: false (workpiece is there)
    UI->>Ctl: setNetFault(sim, ov_drive_in, null)
    Note over Api: the burn starts, the call returns 14.8 s later
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

Only `RUNNING` accepts commands and moves time. `stop` keeps firings in progress. `reset` returns to the initial marking at time zero, same id, no faults, empty trace.
