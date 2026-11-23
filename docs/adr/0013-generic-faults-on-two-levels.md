# ADR 0013: Faults are generic, on two levels

Status: Accepted (2026-11-23). Supersedes [0006](0006-fault-mechanism-in-core-behaviour-in-layouts.md).

## Context
Error cases must be testable for every layout. Before, each layout component coded its own fault modes. With layouts as data, faults must work on any net.

## Decision
- **Sensor faults** change only what is read, never the marking: `STUCK` (keeps the value it had when switched on), `DROPOUT` (reads null), `DELAYED` (reports the value of `delay` ago), `WRONG_VALUE` (a fixed value, or the opposite of a boolean).
- **Net faults** attach to a transition: `BLOCKED` (never fires; requests wait), `DELAYED` (duration × factor), `TOKEN_LOSS` (the tokens it passes on disappear), `MISROUTE` (they go to another place).
- Faults are switched at runtime through the control port (UI, tests); switching is logged (`FAULT_ACTIVATED`, `FAULT_CLEARED`) but invisible to the layout's own interface.
- No probabilities or schedules; a fault stays until cleared.

## Consequences
- Every sensor and transition of every layout can fail without layout code.
- The application notices failures as in the lab: by timeouts, sensor checks or a workpiece that is not where expected.
