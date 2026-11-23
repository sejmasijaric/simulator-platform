# ADR 0006: Fault switching in core, fault behaviour in layouts

Status: Superseded by [0013](0013-generic-faults-on-two-levels.md) (2026-11-23); accepted 2026-10-28

## Context
Error cases must be testable. The physical layout does not report failures; it behaves differently (item not moved, sensor not changing). What a failure looks like depends on the component.

## Decision
- Core: `FaultMode(id, description)`, `Component.getFaultModes()`, `SimulationEngine.setFault(componentId, faultId, active)`, `SimulationContext.isFaultActive(...)`.
- The layout component implements the effect of each fault.
- A fault only changes what the physical component would observably do. Activation is logged (`FAULT_ACTIVATED`, `FAULT_CLEARED`) but not exposed through the hardware-compatible interface.
- Faults are switched manually. No probabilities or schedules.

## Consequences
- The application detects failures as in the lab, e.g. by timeouts or sensor checks.
- Each fault mode must document how it appears on the physical layout so it can be checked there.
