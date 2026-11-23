# ADR 0009: Hardware-compatible adapter per layout

Status: Superseded by [0015](0015-generic-layout-interface-adapter.md) (2026-11-23); proposed 2026-10-28

## Context
An application must run unchanged against simulator and physical layout. The physical interface differs per layout and has no simulation ids.

## Decision
- The generic REST API (`/simulations/...`) is for control and tests only.
- Each layout gets an adapter that reproduces its physical interface (paths, payloads, topics, timing) for one bound simulation, using a `RealTimeDriver`.
- Contract tests compare the adapter with recordings from the physical layout.

## Consequences
- The application switches between simulator and lab by configuration (host, port, broker).
- Status becomes Accepted after the first layout's adapter is validated in the lab.
