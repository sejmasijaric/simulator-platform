# ADR 0005: One single-threaded engine per simulation

Status: Accepted (2026-10-28)

## Context
Commands arrive concurrently (HTTP, UI, real-time driver). Results must be reproducible. Several simulations must run independently.

## Decision
- `SimulationManager` holds one `SimulationEngine` per simulation. The engine owns the `Simulation`, clock, queue and event log.
- All engine operations run on one thread per engine. Phases with equal time run in insertion order.
- The manager assigns ids via `SimulationDefinition.create(UUID)`. `stop` pauses, `reset` rebuilds from the definition with the same id, `remove` requires a non-running simulation.

## Consequences
- No locking in model or components.
- Components and listeners run on the engine thread and must not block.
