# Applications

Example applications, one folder per layout. They show how an application drives a layout and that the same application runs against the simulator and the physical layout.

| Layout | Application | Talks to the layout through |
|---|---|---|
| Fischertechnik Factory 4.0 | [fischertechnik](fischertechnik/README.md) | the factory's own HTTP API, served from the layout YAML ([ADR 0015](../docs/adr/0015-generic-layout-interface-adapter.md)) |

## Developing an application with the factory simulator

Start the simulator, then run the application against the factory's HTTP API on the simulator. For the Fischertechnik example, `--setup` uses the simulator's separate control API to prepare the simulation that is running (the one open in the UI; it creates one only if none runs and never stops one); then the process drives it through the same HTTP API as the physical factory. The web UI observes the run; it does not send machine commands.

```mermaid
sequenceDiagram
    autonumber
    actor Developer
    participant Control as Control API
    participant Engine as Simulation engine
    participant App as Application process
    participant Factory as Factory HTTP API (from the layout YAML)
    participant UI as Web UI

    Developer->>App: run with --url http://localhost:8080 --setup
    App->>Control: GET /simulations
    alt a Fischertechnik simulation is running (opened in the UI)
        Note over App,Control: use it
    else none is running
        App->>Control: POST /simulations {"layoutId": "fischertechnik"}
        App->>Control: POST /simulations/{id}/start
        App->>Control: PUT /simulations/{id}/speed {"factor": 1}
    end
    opt delivery station is empty
        App->>Control: POST /simulations/{id}/tokens (workpiece on delivery_pick_up_station)
    end

    loop each production step
        App->>Factory: command (e.g. /ov/burn) or query (light barrier)
        Factory->>Engine: request token into the command place / read sensors
        Engine->>Engine: fire transitions as time passes
        Engine-->>Factory: request completed / sensor values
        Factory-->>App: the factory's response
        UI->>Control: snapshot and events
    end
    App-->>Developer: process done; inspect the run in the UI
```

Without `--setup`, place the workpiece in the UI before running the process. Against the physical factory, use its URL and put the workpiece in by hand; the application sends the same machine commands.
