# ADR 0010: Web UI observes and prepares, the application drives

Status: Accepted (2026-10-29)

## Context
In use, an application (e.g. a BPMN process in Camunda) sends the machine commands, as in the lab. A UI that also sends machine commands would create runs no application produced.

## Decision
- The UI sends no machine commands. It shows the floor, the running tasks and a story of events, and lets the user place and remove entities, switch faults, pause, change speed, step and reset.
- `ComponentAction` and `SimulationEvent` carry an `origin` (`application`, `control`) so the UI shows who caused what.
- The snapshot contains connections, capacities, fault descriptions and running activities; a definition can provide a `LayoutView` (positions, background image) and `EntityTemplate`s.
- The UI is a React app in `web/`, built by Maven and served by the Spring Boot app.
- Without hardware adapter, `tools/demo/demo_process.py` plays the application against the demo layout.

## Consequences
- Manual machine commands remain possible through the REST control API for tests.
- Layouts without a `LayoutView` are drawn with an automatic arrangement.
