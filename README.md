# Simulation Platform

Generic simulation core for physical lab layouts (Fischertechnik Factory 4.0, Dobot + conveyor).
Applications developed against the simulator should run unchanged on the physical layout.

The design is described in [docs/SIMULATION_CLASS_ARCHITECTURE.md](docs/SIMULATION_CLASS_ARCHITECTURE.md)
(see "Revision 2" for the accepted corrections).

## Status

- Done: generic core (milestones 1–3): model, deterministic engine with timed phases, fault
  mechanism, trace, manager, real-time driver. No framework dependencies.
- Next: REST control adapter, first layout (Fischertechnik) with its hardware-compatible adapter.

## Packages

| Package | Contents |
|---|---|
| `api` | `SimulationDefinition`, `Component`, `Capability`, `ParameterType`, `FaultMode` |
| `model` | `Simulation`, `Entity`, `ComponentState`, snapshots/views, exceptions |
| `execution` | `ComponentAction`, `ComponentExecutionResult`, `ScheduledChange`, `StateChange` |
| `event` | `SimulationEvent`, `EventType`, `EventLog` |
| `runtime` | `SimulationManager`, `SimulationEngine`, `SimulationClock`, `EventQueue`, `SimulationContext`, `RealTimeDriver` |

A layout implements `SimulationDefinition` and its `Component`s; see the test layout in
`src/test/java/ch/hsg/simulation/testlayout` for a minimal example (slots, a two-phase
transporter with a fault, a light-barrier sensor).

## Build and test

Requires Java 21+ and Maven.

```sh
mvn test
```
