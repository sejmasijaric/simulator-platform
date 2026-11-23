# ADR 0008: Event log for trace and adapters

Status: Accepted (2026-10-28), revised 2026-11-23 (Petri net events, listener port)

## Context
Requests, firings and faults must be traceable, e.g. to show which transitions one API call caused. Adapters may need to react to what happens. A failing adapter must not affect the simulation.

## Decision
- Each event (`COMMAND_RECEIVED`, `TRANSITION_STARTED`, `TRANSITION_COMPLETED`, `COMMAND_COMPLETED`, `TOKEN_PLACED`, `TOKEN_REMOVED`, `TOKEN_LOST`, `FAULT_ACTIVATED`, …) carries the simulated time, the request's command id and its origin (`application`, `control`, `simulation`), and is stored in the engine's `EventLog`.
- A firing passes its command id on to the tokens it produces, so later firings of the same request are tied to it too.
- Listeners (`SimulationListener` port) are called after each event. Their exceptions are logged and ignored.
- The marking is the source of truth; adapters read state through the control port (`snapshot`, `readSensors`).

## Consequences
- The log is in memory, unbounded and cleared on reset.
- Adapters that publish periodic status read sensors instead of forwarding events.
