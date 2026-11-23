# ADR 0002: Logical time, optional real-time driver

Status: Accepted (2026-10-28), revised 2026-11-23 (clock inside the engine, drivers owned by the manager)

## Context
Tests must run fast and reproducibly. An application connected to the simulator expects real durations, as on the physical layout.

## Decision
- Each `SimulationEngine` keeps a logical time. The core does not read system time or sleep.
- Time advances only through `step()` (to the end of the next firing in progress), `runUntil(t)` and `runUntilIdle()`.
- `RealTimeDriver` calls `runUntil(elapsed wall time × speed)` every 20 ms while the simulation runs. `SimulationManager` owns one driver per simulation; `SimulationControl.setSpeed` starts or changes it, `stopRealTime` removes it.

## Consequences
- Tests advance time explicitly and finish instantly.
- The layout HTTP adapter switches a simulation to real time (1×) when an application calls it and none is set, then waits on the request's completion where the hardware blocks.
