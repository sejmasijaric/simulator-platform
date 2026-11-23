# ADR 0005: One single-threaded engine per simulation

Status: Accepted (2026-10-28), revised 2026-11-23 (Petri net engine)

## Context
Commands arrive concurrently (layout HTTP calls, UI, real-time driver). Results must be reproducible. Several simulations must run independently.

## Decision
- `SimulationManager` holds one `SimulationEngine` per simulation, created from a layout (`create(layoutId)`). The engine owns the marking, time, queue of firings in progress, faults and event log.
- All engine operations run on one thread per engine. Firings ending at the same time end in the order they started.
- `stop` pauses (firings in progress continue after `start`), `reset` returns to the initial marking at time zero with the same id, `remove` requires a non-running simulation.

## Consequences
- No locking in marking or interpreter.
- Listeners run on the engine thread and must not block.
