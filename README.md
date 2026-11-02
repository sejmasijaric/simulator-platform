# Simulation Platform

Simulation of physical lab layouts (Fischertechnik Factory 4.0, Dobot + conveyor). An application that works against the simulator should work against the physical layout without changes.

- Class contracts: [docs/SIMULATION_CLASS_ARCHITECTURE.md](docs/SIMULATION_CLASS_ARCHITECTURE.md)
- Diagrams: [docs/architecture.md](docs/architecture.md)
- Decisions: [docs/adr](docs/adr/README.md)

## Status

- Implemented: generic core, REST control API, web UI, demo layout, Fischertechnik Factory 4.0 layout with its hardware-compatible HTTP API and MQTT status messages ([docs/layouts/fischertechnik](docs/layouts/fischertechnik/http-api.md)).
- Next: validate the Fischertechnik layout against lab recordings, Dobot + conveyor layout.
- Example applications per layout: [applications](applications/README.md).

## Packages (`ch.hsg.simulation`)

| Package | Classes |
|---|---|
| `api` | `SimulationDefinition`, `Component`, `Capability`, `ParameterType`, `FaultMode` |
| `model` | `Simulation`, `Entity`, `ComponentState`, snapshots, exceptions |
| `execution` | `ComponentAction`, `ComponentExecutionResult`, `ScheduledChange`, `StateChange` |
| `event` | `SimulationEvent`, `EventType`, `EventLog` |
| `runtime` | `SimulationManager`, `SimulationEngine`, `SimulationClock`, `EventQueue`, `SimulationContext`, `RealTimeDriver` |
| `app` | Spring Boot application, wiring, `RealTimeService` |
| `adapters.rest` | REST control API |
| `adapters.fischertechnik` | Hardware-compatible HTTP API and MQTT publisher of the Fischertechnik factory |
| `systems.common` | Components shared by layouts: slot, light barrier |
| `systems.demo` | Demo layout: transporter, conveyor |
| `systems.fischertechnik` | Fischertechnik layout: gripper, oven, transport, milling and sorting machine, warehouse |

The web UI is in `web/` (React, TypeScript, Vite).

Core packages (`api`, `model`, `execution`, `event`, `runtime`) must not import Spring, `app` or `adapters`; `CoreIndependenceTest` checks this. A layout is registered by declaring its `SimulationDefinition` as a Spring bean.

Example layout: `src/test/java/ch/hsg/simulation/testlayout`.

## Build and run

Java 21+, Maven. Maven downloads a project-local Node to build the UI; no Node installation needed.

```sh
mvn package
java -jar target/simulation-platform-0.1.0-SNAPSHOT.jar
```

UI and API run on http://localhost:8080. `-Dweb.skip=true` skips the UI build (faster for backend work).

To see the UI driven like by an application, run an example application: [applications](applications/README.md).

With Docker (no Java needed):

```sh
docker compose up --build                  # simulator on :8080, MQTT broker on :1883
docker compose --profile demo up --build   # plus the demo process
```

The simulator publishes the Fischertechnik factory's status messages (`FTFactory/<STATION>`) to this broker.

UI development with hot reload (backend running on 8080):

```sh
cd web && PATH=$PWD/node:$PATH ./node/npm run dev
```

## Web UI

The application (e.g. a Camunda process via the hardware-compatible adapter) drives the simulator. The UI does not send machine commands. It is for watching, preparing and disturbing a run (ADR 0010):

- Floor: positions, machines with progress, sensors, workpieces moving between positions.
- Story: what happened, with who caused it (application or you).
- Inspector: state, running task, workpieces and faults of a component.
- Place and remove workpieces, switch faults, pause, change speed, step, reset.

## Control API

For the Web UI and tests. Applications use the layout's hardware-compatible adapter (ADR 0009). Durations are ISO-8601 (`PT4S`).

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/simulation-definitions` | | available layouts |
| POST | `/simulations` | `{"definitionId": "..."}` | 201, `{"id": "..."}` |
| GET | `/simulations` | | all snapshots |
| GET | `/simulations/{id}` | | snapshot |
| DELETE | `/simulations/{id}` | | 204; 409 if running |
| POST | `/simulations/{id}/start` \| `stop` \| `reset` | | snapshot |
| POST | `/simulations/{id}/step` | | `{"stepped": true, "time": "PT2S"}` |
| POST | `/simulations/{id}/run-until` | `{"time": "PT10S"}` | snapshot |
| POST | `/simulations/{id}/components/{componentId}/actions[?await=true&timeout=PT60S]` | `{"capability": "...", "parameters": {...}, "origin": "application"}` | 200 completed, 202 accepted, 422 rejected/failed; with `await`: 200 when finished, 504 on timeout |
| PUT | `/simulations/{id}/components/{componentId}/faults/{faultId}` | `{"active": true}` | snapshot |
| POST | `/simulations/{id}/entities` | `{"type": "workpiece", "attributes": {...}, "componentId": "IN"}` | 201, snapshot |
| DELETE | `/simulations/{id}/entities/{entityId}` | | snapshot |
| GET | `/simulations/{id}/events?from=0` | | events from index `from` |
| PUT | `/simulations/{id}/real-time` | `{"speedFactor": 1.0}` | starts the real-time driver |
| DELETE | `/simulations/{id}/real-time` | | stops it |

Errors: 404 unknown definition/simulation/component/fault, 409 wrong lifecycle state, 400 invalid request.
