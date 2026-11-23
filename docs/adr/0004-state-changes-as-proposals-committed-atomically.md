# ADR 0004: Components propose changes, engine applies them atomically

Status: Superseded by [0011](0011-layouts-as-timed-coloured-petri-nets.md): a firing is atomic (2026-11-23); accepted 2026-10-28

## Context
Phases are applied later than the component computes them (ADR 0003). A failing change must not leave the model half-updated.

## Decision
- Components return `StateChange`s: `CreateEntity`, `RemoveEntity`, `MoveEntity`, `UpdateEntityAttributes`, `UpdateComponentState`.
- `Simulation` stores all mutable state, including component state. Components declare `initialState()` and read state via `SimulationContext`.
- `Simulation.apply(batch)` applies all changes of a batch or none. It checks entity and component ids, directed connections and component capacity.
- Entities have free attributes (e.g. colour). Their location is a component id.

## Consequences
- Every change appears in the event log.
- Component state is a `Map<String,Object>`; each component documents its keys.
- `apply` and `setStatus` are public but may only be called by the engine.
