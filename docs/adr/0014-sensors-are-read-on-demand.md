# ADR 0014: Sensors are read on demand

Status: Accepted (2026-11-23). Supersedes [0007](0007-sensors-as-observing-components.md).

## Context
The physical factory has no "sensor changed" events: applications query sensors over HTTP, and the stations publish their full state twice per second whether anything changed or not. Before, sensors were components observed after every commit.

## Decision
- A sensor is a definition in the layout: `occupied`, `count` or `attribute` of a place, optionally translated to the hardware's values (`{true: 0, false: 1}` for a raw light barrier).
- `SimulationControl.readSensors` computes all values from the current marking when asked and applies sensor faults.
- The layout HTTP adapter reads them for query routes; the event publisher reads them on each tick.

## Consequences
- No sensor component, no sensor-changed event; the model stays the only source of truth.
- A delayed-sensor fault keeps a short history of true values while it is active.
