# Simulation Platform

Simulation of physical lab layouts (Fischertechnik Factory 4.0, Dobot + conveyor). An application that works against the simulator should work against the physical layout without changes.

- Class contracts: [docs/SIMULATION_CLASS_ARCHITECTURE.md](docs/SIMULATION_CLASS_ARCHITECTURE.md)
- Diagrams: [docs/architecture.md](docs/architecture.md)
- Decisions: [docs/adr](docs/adr/README.md)

## Status

- Implemented: generic core (model, engine, faults, event log, manager, real-time driver).
- Next: Fischertechnik layout, REST control API, hardware-compatible adapter.

## Packages (`ch.hsg.simulation`)

| Package | Classes |
|---|---|
| `api` | `SimulationDefinition`, `Component`, `Capability`, `ParameterType`, `FaultMode` |
| `model` | `Simulation`, `Entity`, `ComponentState`, snapshots, exceptions |
| `execution` | `ComponentAction`, `ComponentExecutionResult`, `ScheduledChange`, `StateChange` |
| `event` | `SimulationEvent`, `EventType`, `EventLog` |
| `runtime` | `SimulationManager`, `SimulationEngine`, `SimulationClock`, `EventQueue`, `SimulationContext`, `RealTimeDriver` |

Example layout: `src/test/java/ch/hsg/simulation/testlayout`.

## Build

Java 21+, Maven.

```sh
mvn test
```
