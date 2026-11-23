# ADR 0010: Web UI observes and prepares, the application drives

Status: Accepted (2026-10-29), revised 2026-11-23 (Petri net layouts)

## Context
In use, an application (e.g. a BPMN process in Camunda) sends the machine commands, as in the lab. A UI that also sends machine commands would create runs no application produced.

## Decision
- The UI sends no machine commands through the layout's interface. It shows the layout picture with tokens on their places, the net with its tokens, the firings in progress and a story of events grouped by request, and lets the user place and remove tokens, switch sensor and net faults, choose active modules, pause, change speed, step and reset.
- Events carry an `origin` (`application`, `control`, `simulation`) so the UI shows who caused what.
- A layout provides its picture with place positions and workpiece templates in its YAML.
- The UI is a React app in `web/`, built by Maven and served by the Spring Boot app.

## Consequences
- To try out what a request does in the net, the control API can put a request token into a command place directly (`POST /simulations/{id}/commands`); this is marked `control`.
- The UI still has to be adapted to the Petri net snapshot (net view, fault panel, module runner).
