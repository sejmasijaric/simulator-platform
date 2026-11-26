# Applying the simulator to the Dobot layout

First version of the Dobot + conveyor layout, 2026-11-26: [dobot.yaml](../../../src/main/resources/layouts/dobot.yaml). Net pictures: [dobot.svg](../../diagrams/petri-nets/dobot.svg), [module dobot](../../diagrams/petri-nets/dobot-dobot.svg), [module conveyor](../../diagrams/petri-nets/dobot-conveyor.svg).

The question was how much the generic core has to change for a second layout. Answer for this version: **nothing**. The layout is one YAML file (about 250 lines, most of them coordinates, error pages and comments), loaded by the unchanged simulator from `simulation.layouts.dir` or from `src/main/resources/layouts/`.

## What was needed

| Change | Where | Size |
|---|---|---|
| Layout file | `src/main/resources/layouts/dobot.yaml` | 1 new file: 20 places, 8 transitions, 9 sensors, 2 routes |
| Core (`core.*`) | — | none |
| Adapters (`adapter.*`) | — | none |
| Web UI | — | none (picture `dobot-conveyer.png` was already there) |
| Petri net pictures | `scripts/render-petri-nets.py` | none, the script drew the new layout as is |
| Example application | [applications/dobot](../../../applications/dobot/README.md) | 1 new script, the same structure as the Fischertechnik one |

## Checked by running it

The current sources, compiled and started with the file (Java 21):

- Startup: `Layout dobot (Dobot Magician + Conveyor): 20 places, 8 transitions, 2 routes`, next to the Fischertechnik layout, without conflicts.
- `GET /move_to?start=pos1&end=pos8`: 200, the same JSON as app.py (sorted keys, coordinates as arrays, Python tuples in `message`).
- `GET /pick_up_and_transport`: pos → pos moves the stone; to an occupied position it ends up on `table`; from an empty position it succeeds without moving anything.
- The example application `rearrange_stones.py --setup` runs all its nine calls at real time (4 s per move, 6 s per transport) and leaves the stones where they started.
- Four parallel calls: they run one after the other, each answers with its own positions.
- Arm → `conveyor_start`, then the conveyor command: the stone is at `conveyor_end`, the photoelectric sensor reads `true`, the colour sensor reads its colour.
- Errors: missing parameter and invalid position answer with app.py's HTML error pages and messages.

## Where the format was just enough (workarounds in the YAML)

- **Coordinates in the response.** app.py answers with the coordinates of the positions. The net only knows position names. Workaround: every request ends in `dobot_last`, and four attribute sensors translate the name into the tuple and the JSON array with `values`. A core feature "map a parameter to a value" would make this simpler.
- **The last request has to stay readable until the answer is written.** Hence the extra instant step `dobot_finish`. The first version took `dobot_last` inside a timed step, and parallel calls answered `null`; found by the parallel test.
- **Several transitions per case** (stone / no stone at start, end free / occupied), the same pattern as `*_empty` in the Fischertechnik layout.

## Gaps against the physical interface (would need code)

| Gap | Effect | Change needed |
|---|---|---|
| Errors are sent as `application/json` | app.py sends `text/html`; the body is the same | `content-type` per route or error, in `LayoutHttpFilter` (adapter, small) |
| Position `factory` (route into the Fischertechnik factory) | rejected like an unknown position | none: left out on purpose until the lab confirms the route is used; it was modelled in the first draft with four transitions |
| Coordinates `x,y,z,r` as `start`/`end` | rejected with "could not convert string to float" | a parameter type for numbers, or a pose model (core); the professor said coordinates are not the focus |
| Position names are case-sensitive | `POS1` is rejected, app.py accepts it | case-insensitive `values` (adapter, small) |
| `POST /run_xml` (multipart file) | not offered | file parameters (adapter) |
| Kafka topic `Dobot` | not published | a Kafka publisher next to the MQTT one (new adapter) |

None of these are needed to run the Dobot workflow; they matter for applications that rely on these details.

## Open until the lab visit

Durations are guesses. Whether the conveyor and the second arm are in use, how the conveyor is driven, and the arm coordinates of the belt are open: see [lab-checklist.md](lab-checklist.md). `conveyor_start` / `conveyor_end` as arm positions are not in the lab code; the conveyor has no HTTP route yet and runs through the control API (`POST /simulations/{id}/commands` with place `cmd_conveyor_run`).
