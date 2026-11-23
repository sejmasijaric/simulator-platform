# ADR 0001: Layouts in Java, core without framework

Status: Superseded by [0011](0011-layouts-as-timed-coloured-petri-nets.md) (layouts) and [0012](0012-ports-of-the-core.md) (framework-free core) (2026-11-23); accepted 2026-10-28

## Context
The core must be reused for several layouts. Layout behaviour (multi-step movements, sensors, faults) cannot be expressed in a configuration format like YAML without building a small programming language.

## Decision
- A layout implements `SimulationDefinition` and its own `Component` classes in Java.
- The core packages (`api`, `model`, `execution`, `event`, `runtime`) use plain Java 21 and reference no layout.
- REST, MQTT and UI are adapters that depend on the core.

## Consequences
- A new layout adds classes; no core class changes.
- The core is tested with JUnit only, using the test layout in `src/test/.../testlayout`.
- Changing a layout requires a rebuild.
