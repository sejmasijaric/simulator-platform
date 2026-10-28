# Simulation Platform

Simulation of physical lab layouts (Fischertechnik Factory 4.0, Dobot + conveyor). An application that works against the simulator should work against the physical layout without changes.

- Class contracts: [docs/SIMULATION_CLASS_ARCHITECTURE.md](docs/SIMULATION_CLASS_ARCHITECTURE.md)
- Diagrams: [docs/architecture.md](docs/architecture.md)
- Decisions: [docs/adr](docs/adr/README.md)

## Status

- Implemented: generic core (model, engine, faults, event log, manager, real-time driver), REST control API.
- Next: Fischertechnik layout, hardware-compatible adapter.

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

Core packages (`api`, `model`, `execution`, `event`, `runtime`) must not import Spring, `app` or `adapters`; `CoreIndependenceTest` checks this. A layout is registered by declaring its `SimulationDefinition` as a Spring bean.

Example layout: `src/test/java/ch/hsg/simulation/testlayout`.

## Build and run

Java 21+, Maven.

```sh
mvn test
mvn spring-boot:run
```

The API runs on port 8080.

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
| POST | `/simulations/{id}/components/{componentId}/actions` | `{"capability": "...", "parameters": {...}}` | 200 completed, 202 accepted, 422 rejected/failed |
| PUT | `/simulations/{id}/components/{componentId}/faults/{faultId}` | `{"active": true}` | snapshot |
| GET | `/simulations/{id}/events?from=0` | | events from index `from` |
| PUT | `/simulations/{id}/real-time` | `{"speedFactor": 1.0}` | starts the real-time driver |
| DELETE | `/simulations/{id}/real-time` | | stops it |

Errors: 404 unknown definition/simulation/component/fault, 409 wrong lifecycle state, 400 invalid request.
