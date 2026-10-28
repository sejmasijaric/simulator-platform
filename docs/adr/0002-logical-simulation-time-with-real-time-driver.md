# ADR 0002: Logical time, optional real-time driver

Status: Accepted (2026-10-28)

## Context
Tests must run fast and reproducibly. An application connected to the simulator expects real durations, as on the physical layout.

## Decision
- `SimulationClock` is a logical clock. The core does not read system time or sleep.
- Time advances only through `SimulationEngine.step()`, `runUntil(t)` and `runUntilIdle()`.
- `RealTimeDriver` calls `runUntil(elapsed wall time × speed factor)` every tick (default 20 ms).

## Consequences
- Tests advance time explicitly and finish instantly.
- An application-facing adapter must run a `RealTimeDriver` and wait on `completionOf(commandId)` where the hardware blocks.
