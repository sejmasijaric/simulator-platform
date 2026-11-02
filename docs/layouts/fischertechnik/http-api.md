# Fischertechnik Factory 4.0 – HTTP API

Command interface of the physical factory. The simulator's hardware-compatible adapter must reproduce it (ADR 0009).

Source: `smart-factory-python` (`app.py` and `hardware/generic/*_txts.py`), read 2026-11-02. Sensors and MQTT: [mqtt.md](mqtt.md).

## Architecture of the physical side

```
application ──HTTP──▶ app.py (Flask, :5000) ──XML-RPC──▶ one controller per TXT ──▶ motors/sensors
                                                           └──MQTT──▶ FTFactory/<STATION>
```

- `app.py` only validates the `machine` parameter, queues the request and forwards it.
- Each controller (`hardware/generic/*_txts.py`) runs the motors and publishes MQTT.
- Oven (OV) and workstation transport (WT) share one controller.

## General

- Base URL in the lab: `http://192.168.0.21:5000`
- All commands are `GET` with query parameters.
- A call blocks until the task is finished.
- `machine=<type>_<n>` is required on every call. Only `n = 1` exists.
- Optional `prio` (lower first, default 3) orders queued requests.

### Queueing (no "busy" error)

- Each machine has two queues: one for tasks, one for queries.
- A second task for a busy machine waits until the first one finishes. It is never rejected.
- Queries (`status_of_light_barrier`, `state_of_machine`, `check_position`, `get_motor_speed`, `set_motor_speed`, `reset_all_motor_speeds`, `detect_color`, …) are answered while a task runs.

### No precondition checks

- Controllers never check whether a workpiece is at `start`. They run the movement anyway and return success.
- The ontology precondition check in `app.py` exists but is disabled.

## Response

Success, HTTP 200:

```json
{
  "link": "http://192.168.0.21:5000/vgr/pick_up_and_transport",
  "start_time": "28-Oct-2026 (14:03:12.123456)",
  "end_time": "28-Oct-2026 (14:03:20.127968)",
  "process_time": "0:00:08.004512",
  "attributes": []
}
```

- `link`: request URL **without** query string (`request.base_url`).
- `start_time`, `end_time`: `%d-%b-%Y (%H:%M:%S.%f)`.
- `process_time`: Python `timedelta`, `H:MM:SS.ffffff`. The microseconds part is missing when it is 0.
- `attributes`: list with at most one object, see the table below.

Errors are JSON `{"error": "<message>"}`:

| Status | When | Message |
|---|---|---|
| 400 | Controller raised an exception, e.g. unknown position | `400 Bad Request: The machine controller of the addressed machine encountered an error: <class 'ValueError'>:<text>` |
| 404 | `machine` missing | `404 Not Found: No Machine is passed` |
| 404 | `machine` number is not 1, or a required parameter is missing | `404 Not Found: Please make sure that the value of the parameter for the machine is 1` |
| 500 | `machine` without `_` (e.g. `machine=vgr`) | Flask HTML error page |

## Commands for the first layout

| Path | Parameters | Valid values | `attributes` |
|---|---|---|---|
| `/vgr/pick_up_and_transport` | `start`, `end` | see VGR positions; `start != end` | `[]` |
| `/ov/burn` | `time` (optional, default 2) | int | `[]` |
| `/wt/pick_up_and_transport` | `start`, `end` | `oven`, `milling_machine`; `start != end` | `[]` |
| `/mm/mill` | `start`, `end`, `time` (optional, default 2) | `initial`→`ejection` or `initial`→`initial`; `time > 0` | `[]` |
| `/sm/detect_color` | – | – | `[{"color": "white" \| "red" \| "blue" \| "none"}]` |
| `/sm/sort` | `start`, `predefined_ejection_location`, `use_nfc` (optional) | `start`: `initial`, `sm_cb`; location: `sink_1..3`, `corner`, `none` | `[{"sink": 1 \| 2 \| 3}]` for `none`, else `[{"sink": null}]` |

### VGR positions

`delivery_pick_up_station`, `color_detection_delivery_pick_up_station`, `nfc_reader_delivery_pick_up_station`, `oven`, `sink_1`, `sink_2`, `sink_3`, `sm_cb`, `high_bay_warehouse`, `waiting_platform`.
End only: `high_bay_warehouse_holding_position` (keeps holding), `high_bay_warehouse_delivery_station`.
A coordinate `(x,y,z)` is also accepted.

Unknown position → 400 `Position <name> does not exist`.

### Behaviour that matters for the simulator

- **`/ov/burn`**: `time` is not seconds. The oven blinks `time × 8` times at 0.3 s, so burning takes `time × 2.4 s`. Door opening and driving add about 10 s.
- **`/mm/mill`**: `time` is seconds of milling. With `end=ejection` the milling machine pushes the workpiece onto its own belt and carries it to the sorting machine's start. The sorting machine does **not** fetch it.
- **`/mm/mill` with an invalid `start`/`end` pair** does nothing and returns 200.
- **`/sm/detect_color`** runs the belt until light barrier 3, measures brightness on the way and stops. It moves the workpiece even though it is a query.
- **`/sm/sort`** also runs detection first. If `detect_color` already moved the workpiece to light barrier 3, the second detection reads nothing (`none`). With `predefined_ejection_location=none` this raises an error (400). With a fixed sink it still works.
- **`use_nfc`** is read with Python `bool(...)`, so any value, including `false`, turns it on. That adds 1 s. Only leaving it out turns it off.
- A non-integer `time` on `/ov/burn` or `/mm/mill` gives a 500 (HTML error page).
- Colour thresholds (lowest brightness seen): `< 1200` white, `1200–1500` red, `1500–1620` blue, else `none`.

### Process order used in the lab

1. `/vgr/pick_up_and_transport?machine=vgr_1&start=delivery_pick_up_station&end=oven`
2. `/ov/burn?machine=ov_1`
3. `/wt/pick_up_and_transport?machine=wt_1&start=oven&end=milling_machine`
4. `/mm/mill?machine=mm_1&time=10&start=initial&end=ejection`
5. `/sm/detect_color?machine=sm_1`
6. `/sm/sort?machine=sm_1&start=initial&predefined_ejection_location=sink_1`

## Durations

From `workflows/demos_openday/Production_Log.csv` (March 2021, older setup). Use as starting values until the lab recordings arrive.

| Command | Measured (s) |
|---|---|
| `vgr pick_up_and_transport` | 30–72, mean 51 (depends on positions) |
| `ov burn` | 49 |
| `wt pick_up_and_transport` | 40–48 |
| `mm` milling + transport (`drill` + `transport_from_to` in the log) | 32–36 + 24 |
| `sm sort` | 11–16 |

## Using the simulator

- Base URL: `http://localhost:8080` instead of `http://192.168.0.21:5000`. Paths, parameters and responses are the same.
- Create and start one Fischertechnik simulation in the web UI first. With none running, calls return 503; with several, 409.
- The simulator switches the simulation to real time (1×) if needed. A higher speed in the UI makes the commands finish faster.
- Implemented: the six commands above, `/process/unload_from_hbw_container?color=`, `status_of_light_barrier` (vgr, ov, mm, sm) and `state_of_machine`.
- Workpieces are placed in the UI (or with `POST /simulations/{id}/entities`). The positions are the API names plus `oven_chamber`, `milling_machine_belt`, `sorting_machine_start`, `color_sensor` and the warehouse stock `high_bay_warehouse_rack` (up to 9 workpieces).
- Light barriers are named `<machine>_lb<input>`, e.g. `sm_1_lb6`.

Differences from the physical factory:

- `/sm/detect_color` with no workpiece returns `none` after 5 s; the physical belt runs forever.
- `/sm/sort` with `start=sm_cb` or `predefined_ejection_location=corner` returns 400 (not simulated yet).
- `/sm/sort` with location `none` and no detectable workpiece returns 400 right away instead of running the belt first.
- The VGR accepts `delivery_pick_up_station`, `color_detection_delivery_pick_up_station`, `high_bay_warehouse`, `oven`, `sink_1..3` and (as end) `high_bay_warehouse_delivery_station`. Other positions return 400.
- `/process/unload_from_hbw_container` without a stored workpiece of that colour returns 400; the physical call waits forever.
- Durations are starting values (see above) until the lab recordings are in.

## Endpoints not yet implemented in the simulator

The physical factory offers these too. The simulator does not support them yet. They are listed here so they can be added later.

- Every machine: `calibrate`, `state_of_machine`, `get_motor_speed`, `set_motor_speed`, `reset_all_motor_speeds`; most also `status_of_light_barrier?lb=<n>` and `check_position?position=<name>`.
- VGR: `move_to`, `stop_vacuum_suction`, `read_color`.
- WT: `move_to`.
- MM: `move_from_to`, `transport_from_to`.
- High-bay warehouse: `store`, `unload`, `change_buckets`, `get_workpiece_by_color`, `get_amount_of_stored_workpieces`, `get_slot_number_of_workpiece_by_color`.
- Composite processes: `/process/store`, `/process/produce?color=`, `/process/store_in_hbw_container`, `/process/unload_from_hbw_container`.
