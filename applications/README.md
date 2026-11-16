# Applications

Example applications, one folder per layout. They show how an application drives a layout and that the same application runs against the simulator and the physical layout.

| Layout | Application | Talks to the layout through |
|---|---|---|
| Demo line | [demo](demo/README.md) | the simulator's control API (the demo layout has no hardware) |
| Fischertechnik Factory 4.0 | [fischertechnik](fischertechnik/README.md) | the factory's own HTTP API (ADR 0009) |

## Developing an application with the factory simulator

Start the simulator, then run the application against its factory-compatible HTTP API. For the Fischertechnik example, `--setup` uses the simulator's separate control API to create and prepare a simulation, then the process drives it through the same HTTP API used by the physical factory. The Web UI observes the run; it does not issue machine commands.

![Developing an application with the factory simulator](../docs/diagrams/application-development-sequence.png)

Vector version: [application-development-sequence.svg](../docs/diagrams/application-development-sequence.svg).

```mermaid
sequenceDiagram
    autonumber
    actor Developer
    participant Simulator as Simulator (:8080)
    participant Control as Simulator control API
    participant Engine as Simulation engine
    participant App as Application process
    participant Factory as Factory-compatible HTTP API
    participant UI as Web UI

    Developer->>Simulator: Start simulator
    Simulator->>Simulator: Register Fischertechnik layout
    Developer->>UI: Open simulator in browser
    Developer->>App: Run with --url http://localhost:8080 --setup

    App->>Control: GET /simulations
    Control-->>App: Existing simulations
    opt A Fischertechnik simulation is already running
        App->>Control: POST /simulations/{id}/stop
        Control->>Engine: Stop simulation
    end
    App->>Control: POST /simulations (definitionId: fischertechnik)
    Control->>Engine: Create simulation
    Engine-->>Control: Simulation id
    Control-->>App: Simulation id
    App->>Control: POST /simulations/{id}/start
    Control->>Engine: Start simulation
    App->>Control: PUT /simulations/{id}/real-time
    Control->>Engine: Start real-time driver
    App->>Control: POST /simulations/{id}/entities (workpiece at delivery station)
    Control->>Engine: Place workpiece

    loop Each production step
        App->>Factory: Read light barrier or request machine action
        Factory->>Engine: Observe state or execute action
        Engine->>Engine: Advance simulation and apply scheduled changes
        Engine-->>Factory: Sensor state or completed action
        Factory-->>App: Factory-compatible response
        UI->>Control: Read simulation snapshot and events
        Control-->>UI: Current state and event history
    end
    App-->>Developer: Process completes, inspect the run in the UI
```

Without `--setup`, prepare the workpiece in the UI before running the process. Against the physical factory, use its URL and put the workpiece in by hand; the application still sends its machine commands through the factory-compatible API.
