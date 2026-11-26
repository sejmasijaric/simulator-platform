# Simulation Platform

Simulation of physical lab layouts (Fischertechnik Factory 4.0; Dobot + conveyor, first version). An application that works against the simulator works against the physical layout without changes.

Each layout is a **timed, coloured Petri net in a YAML file**: places for positions, machine states and request queues, transitions for what the machines can do, sensors derived from where the tokens are, and the layout's own HTTP API and MQTT messages. One generic core runs any such file; no layout needs Java code.

- Architecture: [docs/architecture.md](docs/architecture.md)
- Layout format: [docs/layouts/layout-format.md](docs/layouts/layout-format.md); how the YAML is a Petri net: [docs/layouts/yaml-to-petri-net.md](docs/layouts/yaml-to-petri-net.md)
- Petri net diagrams of every layout: [docs/diagrams/petri-nets](docs/diagrams/petri-nets/README.md), generated with `python3 scripts/render-petri-nets.py` (needs PyYAML and Graphviz)
- Decisions: [docs/adr](docs/adr/README.md)
- Physical interfaces: [Fischertechnik](docs/layouts/fischertechnik/http-api.md), [Dobot](docs/layouts/dobot/http-api.md)

## Status

- Implemented: the Petri net core (guards, inhibitor arcs, parameter-dependent durations, results, settings such as motor speeds, sensors for the running step), generic faults on sensor and net level, the YAML loader, the REST control API, the generic layout HTTP API and MQTT publisher, and the Fischertechnik factory as [fischertechnik.yaml](src/main/resources/layouts/fischertechnik.yaml).
- Fischertechnik factory: its own HTTP API and MQTT messages as the lab's applications use them, including motor speeds and the controllers' task texts; the warehouse is out of service, as in the lab. What is and is not simulated: [ADR 0016](docs/adr/0016-fischertechnik-factory-scope.md).
- Web UI: the factory photo with live workpieces, machines and sensors; an inspector for places, transitions and sensors with fault controls; the layout's HTTP API to try requests; each station's MQTT message and motor speeds; the trace.
- Dobot + conveyor: first version as [dobot.yaml](src/main/resources/layouts/dobot.yaml), with no change to the core; see [applying-the-simulator.md](docs/layouts/dobot/applying-the-simulator.md).
- Next: calibrate durations, motor-speed scaling and MQTT fields with lab recordings; the Dobot's conveyor, second arm and durations from the lab.
- Example application: [applications](applications/README.md).

## Packages (`ch.hsg.simulation`)

| Package | Content |
|---|---|
| `core.port` | The three ports: `SimulationControl` (driving), `LayoutSource`, `SimulationListener` (driven); `Snapshot`, `CommandHandle`, `SimulationStatus` |
| `core.net` | `NetDefinition` (a layout), `Marking`, `Token`, `NetInterpreter` (firing rules), `Templates` |
| `core.runtime` | `SimulationManager` (implements the control port), `SimulationEngine`, `EventQueue`, `RealTimeDriver` |
| `core.fault` | `Faults`, `SensorFault`, `NetFault` |
| `core.event` | `EventLog`, `SimulationEvent`, `EventType` |
| `adapter.in.web` | `ControlController` (REST control API), `LayoutHttpApi` + `LayoutHttpFilter` (layouts' own HTTP APIs) |
| `adapter.out.layout` | `YamlLayoutLoader`, `LayoutYamlParser` |
| `adapter.out.messaging` | `MqttEventPublisher` |
| `app` | Spring Boot application and wiring |

The core uses no Spring and no adapter; `CoreIndependenceTest` checks this. Layouts: `src/main/resources/layouts/*.yaml`, plus any folder set in `simulation.layouts.dir`. The web UI is in `web/` (React, TypeScript, Vite).

## Build and run

Java 21+, Maven. Maven downloads a project-local Node to build the UI; no Node installation needed.

```sh
mvn package
java -jar target/simulation-platform-0.1.0-SNAPSHOT.jar
```

UI and APIs run on http://localhost:8080. `-Dweb.skip=true` skips the UI build (faster for backend work).

With Docker (no Java needed):

```sh
docker compose up --build    # simulator on :8080, MQTT broker on :1883
```

The simulator publishes each layout's messages (e.g. `FTFactory/<STATION>`) to this broker.

UI development with hot reload (backend running on 8080):

```sh
cd web && PATH=$PWD/node:$PATH ./node/npm run dev
```

## Layout APIs

Every route a layout declares is served on the simulator with the same path, parameters, responses and errors as on the physical layout, e.g. `GET /ov/burn?machine=ov_1`. Calls go to the most recently started running simulation of that layout, switched to real time if needed. See the layout's interface notes, e.g. [Fischertechnik](docs/layouts/fischertechnik/http-api.md).

## Control API

For the web UI, tests and setup scripts. Applications use the layout's own API. Durations are ISO-8601 (`PT4S`).

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/layouts`, `/layouts/{layoutId}` | | layouts (net, sensors, routes, picture) |
| POST | `/simulations` | `{"layoutId": "..."}` | 201, `{"id": "..."}` |
| GET | `/simulations`, `/simulations/{id}` | | snapshots: marking, firings in progress, sensors, faults |
| DELETE | `/simulations/{id}` | | 204; 409 if running |
| POST | `/simulations/{id}/start` \| `stop` \| `reset` | | snapshot |
| POST | `/simulations/{id}/step` | | `{"stepped": true, "time": "PT5S"}` |
| POST | `/simulations/{id}/run-until` | `{"time": "PT10S"}` | snapshot |
| PUT / DELETE | `/simulations/{id}/speed` | `{"factor": 2.0}` | real time on (at that speed) / off |
| POST | `/simulations/{id}/tokens` | `{"place": "oven", "attributes": {"color": "red"}}` | 201, `{"id": "token-1"}` |
| DELETE | `/simulations/{id}/tokens/{tokenId}` | | snapshot |
| PUT / DELETE | `/simulations/{id}/faults/sensors/{sensor}` | `{"type": "STUCK"}` (`DROPOUT`, `DELAYED` + `delay`, `WRONG_VALUE` + `value`) | snapshot |
| PUT / DELETE | `/simulations/{id}/faults/transitions/{transition}` | `{"type": "BLOCKED"}` (`DELAYED` + `factor`, `TOKEN_LOSS`, `MISROUTE` + `place`) | snapshot |
| PUT | `/simulations/{id}/modules` | `{"modules": ["ov"]}` (empty = all) | snapshot |
| POST | `/simulations/{id}/commands` | `{"place": "cmd_ov_burn", "parameters": {...}, "returnsAfter": ["ov_burn"], "await": true}` | request token put directly into the net, to try it out |
| GET | `/simulations/{id}/sensors` | | sensor values as applications read them |
| GET | `/simulations/{id}/events?from=0` | | trace from index `from` |

Errors: 404 unknown layout/simulation/place/transition/sensor/token, 409 wrong lifecycle state, 400 invalid request.
