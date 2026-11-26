# Architecture Decision Records

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-java-coded-layouts-and-framework-free-core.md) | Layouts in Java, core without framework | Superseded by 0011, 0012 |
| [0002](0002-logical-simulation-time-with-real-time-driver.md) | Logical time, optional real-time driver | Accepted, revised |
| [0003](0003-timed-multi-phase-actions.md) | Actions consist of timed phases | Superseded by 0011 |
| [0004](0004-state-changes-as-proposals-committed-atomically.md) | Components propose changes, engine applies them atomically | Superseded by 0011 |
| [0005](0005-one-serialized-engine-per-simulation.md) | One single-threaded engine per simulation | Accepted, revised |
| [0006](0006-fault-mechanism-in-core-behaviour-in-layouts.md) | Fault switching in core, fault behaviour in layouts | Superseded by 0013 |
| [0007](0007-sensors-as-observing-components.md) | Sensors derive their value from the model | Superseded by 0014 |
| [0008](0008-event-log-as-trace-and-integration-point.md) | Event log for trace and adapters | Accepted, revised |
| [0009](0009-hardware-compatible-interface-per-layout.md) | Hardware-compatible adapter per layout | Superseded by 0015 |
| [0010](0010-web-ui-observes-application-drives.md) | Web UI observes and prepares, the application drives | Accepted, revised |
| [0011](0011-layouts-as-timed-coloured-petri-nets.md) | Layouts are timed, coloured Petri nets declared in YAML | Accepted |
| [0012](0012-ports-of-the-core.md) | The core is reached only through three ports | Accepted |
| [0013](0013-generic-faults-on-two-levels.md) | Faults are generic, on two levels | Accepted |
| [0014](0014-sensors-are-read-on-demand.md) | Sensors are read on demand | Accepted |
| [0015](0015-generic-layout-interface-adapter.md) | One generic adapter serves every layout's own interface | Accepted |
| [0016](0016-fischertechnik-factory-scope.md) | What the Fischertechnik factory simulation covers | Accepted |
| [0017](0017-settings-and-running-step-sensors.md) | Settings (e.g. motor speeds) and sensors for the running step | Accepted |

Superseded ADRs stay as history; each names the ADR that replaced it.
