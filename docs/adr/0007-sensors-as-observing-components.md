# ADR 0007: Sensors derive their value from the model

Status: Superseded by [0014](0014-sensors-are-read-on-demand.md) (2026-11-23); accepted 2026-10-28

## Context
A sensor value follows from the model, e.g. a light barrier is interrupted when its position is occupied. Actuators should not have to update sensors.

## Decision
- A sensor is a `Component` that overrides `observe(context)` and returns its current `ComponentState`.
- After every commit and fault change, the engine calls `observe` once on all components and stores changed states.
- `observe` cannot propose other changes.
- A stuck sensor returns no new state.

## Consequences
- Actuators do not reference sensors.
- Each commit iterates over all components.
