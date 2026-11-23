# ADR 0012: The core is reached only through three ports

Status: Accepted (2026-11-23). Keeps the framework-free core of [0001](0001-java-coded-layouts-and-framework-free-core.md).

## Context
The platform follows a hexagonal architecture. Before, adapters called core classes directly (`SimulationManager`, `SimulationEngine`) and layouts were plugged in as Spring beans.

## Decision
- The core (`ch.hsg.simulation.core`) uses plain Java 21, no Spring, no adapter; `CoreIndependenceTest` checks this.
- Three ports, Java interfaces owned by the core (`core.port`):
  - `SimulationControl` (driving, implemented by `SimulationManager`): layouts, lifecycle, commands and sensor reads of the layout's interface, tokens, faults, modules, time, trace.
  - `LayoutSource` (driven, implemented by `YamlLayoutLoader`): where layouts come from.
  - `SimulationListener` (driven): what happens inside simulations, e.g. for a UI stream.
- Adapters live in `ch.hsg.simulation.adapter.in.*` (driving) and `adapter.out.*` (driven). `app` is the composition root that wires them.

## Consequences
- The core runs and is tested without Spring, with a layout written in Java (`TestNets`).
- A new way to load layouts or to publish events is a new adapter, not a core change.
- The package structure follows BuckPal's `adapter.in` / `adapter.out` split; the use cases are one port (`SimulationControl`) rather than one interface per use case (see the open question in the component overview).
