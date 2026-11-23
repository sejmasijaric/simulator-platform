# ADR 0015: One generic adapter serves every layout's own interface

Status: Accepted (2026-11-23). Supersedes [0009](0009-hardware-compatible-interface-per-layout.md).

## Context
An application must run unchanged against simulator and physical layout (interface compatibility). Before, each layout had a hand-written adapter reproducing its interface.

## Decision
- The layout YAML declares its HTTP routes (`http.routes`) and published messages (`events`).
- One generic HTTP adapter (`LayoutHttpApi`, behind a servlet filter) answers them:
  - It checks parameters in the declared order and answers with the declared error (status and body) when one is missing or invalid.
  - A **command** route puts a request token into its command place and answers once one of its `returns-after` transitions has fired for that request.
  - A **query** route reads a sensor and fires nothing.
  - Bodies are templates (`${result.sink}`, `${read}`, `${time.elapsed|timedelta}`), optionally wrapped in the layout's envelope.
- One generic event publisher sends each declared message at its interval with the current sensor values.
- Both talk to the most recently started running simulation of the layout and switch it to real time if needed.
- Quirks a template cannot express can be named Java hooks (not needed so far).

## Consequences
- Applications switch between lab and simulator by configuration (host, port, broker) only.
- The command place doubles as the controller's queue: a request for a busy machine waits instead of failing, as on the physical factory.
- Contract tests compare the answers with recordings from the physical layout.
