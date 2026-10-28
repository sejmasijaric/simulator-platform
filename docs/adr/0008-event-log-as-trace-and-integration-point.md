# ADR 0008: Event log for trace and adapters

Status: Accepted (2026-10-28)

## Context
Actions, state changes and faults must be traceable. Adapters need to react to changes. A failing adapter must not affect the simulation.

## Decision
- Each committed event gets the simulated time and command id and is stored in the engine's `EventLog`.
- Listeners are called after the commit. Their exceptions are logged and ignored.
- The model is the source of truth; adapters read state via `snapshot()`.

## Consequences
- The log is in memory, unbounded and cleared on reset.
- Adapters that publish periodic status read snapshots instead of forwarding events.
