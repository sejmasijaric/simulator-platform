# Fischertechnik Factory 4.0 – HTTP API

Command interface of the physical factory. The simulator's hardware-compatible adapter must reproduce it (ADR 0009).

This is a subset: only the endpoints listed here are known. The factory's API server offers more. The full list will be taken from the server source.

Verified: path and parameters were used against the physical factory.
Unverified: assumed, must be checked in the lab.

## General

- Base URL in the lab: `http://192.168.0.21:5000`
- All commands are `GET` with query parameters.
- A call blocks until the task is finished (unverified for all endpoints).
- Every command has a `machine` parameter naming the machine instance.

## Commands

| Machine | Path | Parameters | Verified |
|---|---|---|---|
| Vacuum gripper | `/vgr/pick_up_and_transport` | `machine=vgr_1`, `start`, `end` | yes |
| Oven | `/ov/burn` | `machine=ov_1` | yes |
| Oven | `/ov/burn` | `time` (optional) | no |
| Workstation transport | `/wt/pick_up_and_transport` | `machine=wt_1`, `start`, `end` | yes |
| Milling machine | `/mm/mill` | `machine=mm_1`, `time`, `start`, `end` | yes |
| Milling machine | `/mm/move_from_to` | `machine=mm_1`, `start`, `end` | no |
| Sorting machine | `/sm/detect_color` | `machine=sm_1` | yes |
| Sorting machine | `/sm/sort` | `machine=sm_1`, `start`, `predefined_ejection_location` | yes |
| Sorting machine | `/sm/set_motor_speed` | `machine=sm_1`, `motor`, `speed` | no |

### Parameter values

| Path | Parameter | Values used (verified) | Further values (unverified) |
|---|---|---|---|
| `/vgr/pick_up_and_transport` | `start` | `delivery_pick_up_station` | `oven`, `end`, `sink_1`, `sink_2`, `sink_3` |
| | `end` | `oven` | `delivery_pick_up_station`, `end`, `sink_1`, `sink_2`, `sink_3` |
| `/wt/pick_up_and_transport` | `start` | `oven` | `milling_machine` |
| | `end` | `milling_machine` | `oven` |
| `/mm/mill` | `time` | `10` (unit unknown, probably seconds) | |
| | `start` | `initial` | |
| | `end` | `ejection` | |
| `/sm/sort` | `start` | `initial` | |
| | `predefined_ejection_location` | `sink_1`, `sink_2`, `sink_3` | |

### Process order used

1. `/vgr/pick_up_and_transport?machine=vgr_1&start=delivery_pick_up_station&end=oven`
2. `/ov/burn?machine=ov_1`
3. `/wt/pick_up_and_transport?machine=wt_1&start=oven&end=milling_machine`
4. `/mm/mill?machine=mm_1&time=10&start=initial&end=ejection`
5. `/sm/detect_color?machine=sm_1`
6. `/sm/sort?machine=sm_1&start=initial&predefined_ejection_location=sink_1`

## Response (unverified)

```json
{
  "attributes": [],
  "start_time": "28-Oct-2026 (14:03:12.123456)",
  "end_time": "28-Oct-2026 (14:03:20.127968)",
  "process_time": "0:00:08.004512",
  "link": "http://192.168.0.21:5000/vgr/pick_up_and_transport?machine=vgr_1&start=delivery_pick_up_station&end=oven"
}
```

- Timestamps: `dd-MMM-yyyy (HH:mm:ss.SSSSSS)`, English month abbreviation.
- `process_time`: `H:MM:SS.ffffff`.
- `link`: the request URL.
- `attributes` is empty except for:
  - `/sm/detect_color`: `[{"color": "white"}]`, lowercase, `none` if no workpiece.
  - `/sm/sort`: `[{"sink": "..."}]`.

## Open questions for the lab

1. Exact response body and HTTP status for success, unknown parameter values and errors.
2. Behaviour when a machine is called while busy, or when there is no workpiece at `start`.
3. Duration of each command.
4. Meaning of `time` for `/ov/burn` and `/mm/mill`.
5. Possible colour values of `/sm/detect_color`.
6. Whether the sorting machine takes the workpiece from the milling machine's ejection automatically.
