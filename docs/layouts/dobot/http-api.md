# Dobot + conveyor – HTTP API

Command interface of the lab's Dobot Magician. The simulator's hardware-compatible adapter reproduces it (ADR 0009).

Source: `dobot-main` (`src/app.py`, `src/dobot.py`), found on the lab computer, read 2026-11-16. Whether this is the version the lab runs today is not confirmed yet, see [lab-checklist.md](lab-checklist.md).

## Architecture of the physical side

```
application ──HTTP──▶ app.py (Flask, :5000) ──USB serial──▶ Dobot Magician (suction cup)
                         └──Kafka──▶ topic "Dobot" on 192.168.1.112:9092
```

- The conveyor is not driven by this code.
- The serial port (`/dev/tty.usbserial-110`) and Kafka address are hard-coded.

## General

- Base URL in the lab: `http://<lab computer>:5000` (port from `app.run`). Simulator: `http://localhost:8080`.
- A call blocks until the arm has finished (assumed: `pydobotplus` `move_to` waits by default).
- Flask runs threaded, so two parallel calls interleave their serial commands on the real arm. The simulator runs them one after the other.
- No precondition checks: picking from an empty position runs the suction anyway and returns success.

## Positions

`start` and `end` take one of (checked in this order, case-insensitive):

| Value | Meaning |
|---|---|
| `pos1` … `pos8` | Stone positions, a 2 × 4 grid. Odd positions are closer to the arm. |
| `factory` | Route into the Fischertechnik factory and back, not a pose |
| `x,y,z,r` | Coordinates in mm and degrees, exactly four floats |

| Name | x | y | z | r |
|---|---|---|---|---|
| `pos1` | 248.3179 | 60.3962 | -80.2133 | -135.4775 |
| `pos2` | 272.3332 | 63.7447 | -73.8629 | -134.4575 |
| `pos3` | 245.3788 | 89.3134 | -73.6323 | -127.9375 |
| `pos4` | 270.5498 | 89.5389 | -74.2002 | -128.9675 |
| `pos5` | 244.3448 | 115.0324 | -74.6829 | -121.4675 |
| `pos6` | 270.0023 | 114.7764 | -75.2266 | -123.6475 |
| `pos7` | 243.6652 | 139.5487 | -74.7541 | -116.8775 |
| `pos8` | 268.6874 | 140.1683 | -76.0964 | -119.1275 |
| home | 182.0821 | -3.4211 | 38.5537 | 0 |
| factory drop | 2.2468 | 295.7190 | 35.6211 | 127.5453 |

Factory route in: home → (244.8616, 57.5479, 44.9715, -134.9775) → (13.8968, 190.8185, 54.4975, 123.8153) → above factory (2.2468, 295.7189, 50.6452) → drop, suction off → above factory → home. The route out is the reverse, without suction.

## `GET /move_to?start=&end=`

Moves to `start`, then to `end`. Never touches the suction.

```json
{"end_position":[200.0,0.0,50.0,0.0],"message":"Moved from (248.3179, 60.3962, -80.2133, -135.4775) to (200.0, 0.0, 50.0, 0.0)","start_position":[248.3179,60.3962,-80.2133,-135.4775],"status":"success"}
```

Keys are sorted (Flask `jsonify`). `message` uses Python's tuple formatting.

| Case | Response |
|---|---|
| `start` or `end` missing or empty | 400 `Both start and end parameters are required` |
| Invalid position | 400 `Movement error: 400 Bad Request: Invalid position format: could not convert string to float: 'pos9'` |
| Wrong number of values | 400 `Movement error: 400 Bad Request: Invalid position format: Exactly 4 values (x,y,z,r) required` |
| `start=factory` | 400 `Movement error: too many values to unpack (expected 4)`, arm does not move |
| `end=factory` | Arm moves to `start`, then 400 with the same message |

## `GET /pick_up_and_transport?start=&end=`

Moves to `start`, suction on, moves to `end`, suction off. With `end=factory` it drives the factory route and releases at the factory drop. Same response as `move_to`; `start_position` / `end_position` are `"factory"` for the factory.

Errors are the same as for `move_to`, but parse errors are not wrapped in `Movement error: …`.

**Quirk:** with `start=factory` the code drives into the factory and back with the suction off and switches it on only at the home position. It picks nothing up. The simulator does the same.

## `POST /run_xml` (multipart)

Runs a DobotStudio playback file: calibrate (home run), move home, each row's `item_2` … `item_5` as x, y, z, r, then home. Other columns (motion type, suction, pauses) are ignored. Rows with non-numeric coordinates are skipped.

| Request | Response |
|---|---|
| No part `file` | 400 `The browser (or proxy) sent a request that this server could not understand.` |
| Part `file` without a part `file_path` | 200 `{"message": "No file part."}`, nothing moves |
| Empty filename | 200 `{"message": "No selected file."}` |
| Both parts | Runs, then 200 `{"message": "Dobot has finished executing commands."}` |
| Invalid XML | Calibrates and moves home, then 500 |

The README's `curl -F "file=@movements.xml"` therefore never runs anything; a `file_path` part has to be sent too. This looks like a bug in the lab code.

## Errors

Errors are Werkzeug's HTML pages (`text/html; charset=utf-8`), quotes escaped (`&#39;`):

```html
<!doctype html>
<html lang=en>
<title>400 Bad Request</title>
<h1>Bad Request</h1>
<p>Both start and end parameters are required</p>
```

Simulator only: 503 `{"error": …}` when no Dobot simulation is running, 409 when several are.

## Kafka (not simulated yet)

After every move the lab code sends `{"position": {"x": …, "y": …, "z": …, "r": …}}` to topic `Dobot`. The calibration also sends the pose and a text line. The simulator does not publish these yet; the arm's pose is in the snapshot (`x`, `y`, `z`, `r`, `suction`).

## Simulation model

Modelled as a Petri net in [dobot.yaml](../../../src/main/resources/layouts/dobot.yaml) (first version, 2026-11-26); what it covers and what it does not: [applying-the-simulator.md](applying-the-simulator.md).

- Positions are places (`pos1` … `pos8`, `factory`, `table`); coordinates `x,y,z,r` are not simulated and are rejected.
- A stone dropped on an occupied position lands on `table`. Picking from an empty position succeeds without moving anything.
- Durations are guesses: move 4 s, pick 3 s, drop 3 s, factory route 10 s.
- Conveyor (module `conveyor`): `cmd_conveyor_run` moves a stone from `conveyor_start` to `conveyor_end` in 5 s, through the control API only. The arm reaches the belt as `conveyor_start` / `conveyor_end`, which are not in the lab code.
- Faults are the generic ones, e.g. `TOKEN_LOSS` on `dobot_pick`.
