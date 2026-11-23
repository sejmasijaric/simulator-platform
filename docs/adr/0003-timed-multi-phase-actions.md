# ADR 0003: Actions consist of timed phases

Status: Superseded by [0011](0011-layouts-as-timed-coloured-petri-nets.md): phases became timed transitions (2026-11-23); accepted 2026-10-28

## Context
A physical task changes the world in steps. Example: a gripper first moves an item to its hold position, later to the target. The intermediate state is observable.

## Decision
- `ComponentExecutionResult` holds a list of `ScheduledChange(offset, changes, events)`.
- The engine schedules each phase at `start + offset` and commits it when time reaches it. The last phase completes the action (`ACTION_COMPLETED`).
- Statuses: `COMPLETED` (one phase at offset 0), `ACCEPTED` (timed), `REJECTED`, `FAILED`. The last two carry no changes.

## Consequences
- Several components can act at the same time.
- A phase is validated when it is committed. If invalid, it is skipped (`CHANGE_REJECTED`) and later phases still run.
