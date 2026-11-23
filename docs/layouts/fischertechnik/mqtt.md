# Fischertechnik Factory 4.0 – MQTT and sensors

Source: `hardware/generic/*_txts.py` in `smart-factory-python`, plus one recorded message per station (2026-04-01).

## Broker

- Host in the source: `ftsim.weber.ics.unisg.ch:1883`, user `ftsim`. The lab broker may differ (check in the lab).
- Topic: `FTFactory/<STATION>`, e.g. `FTFactory/VGR_1`. Subscribe with `FTFactory/#`.
- Each station publishes its full state **twice per second**, whether something changed or not. QoS 0, not retained.

## Common fields

| Field | Meaning |
|---|---|
| `id` | random UUID per message |
| `station` | `VGR_1`, `OV_1`, `WT_1`, `MM_1`, `SM_1`, `HBW_1` |
| `timestamp` | `YYYY-MM-DD HH:MM:SS.ff` (local time, 2 decimals) |
| `current_state` | `ready` or `not ready` (while a task runs) |
| `current_task` | text, e.g. `burning the workpiece for 2 seconds`; empty when ready |
| `current_task_duration` | seconds since the task started, 0 when ready |
| `current_sub_task` | text of the current step, e.g. `opening the oven door` |
| `mN_speed` | motor PWM, −512…512, 0 = stopped |
| `oN_*` | valve/compressor PWM, 0 or 512 |

**Light barriers and switches are raw inputs: `1` = free / not pressed, `0` = interrupted / pressed.** The HTTP API inverts this (`interrupted: true` when the raw value is 0).

## Sensors per station

| Station | Field | Watches |
|---|---|---|
| VGR_1 | `i1/i2/i3_pos_switch` | reference switches of the X, Y, Z axes |
| | `i7_light_barrier` | delivery/pick-up station (input) |
| | `i4_light_barrier` | HBW delivery station (output) |
| | `i8_color_sensor` | colour at the colour-detection station, raw brightness |
| | `current_pos_*`, `target_pos_*` | gripper position (VGR only) |
| OV_1 | `i1_pos_switch` | platform inside the oven |
| | `i2_pos_switch` | platform outside the oven |
| | `i5_light_barrier` | workpiece on the oven platform |
| WT_1 | `i3_pos_switch` | crane at the oven |
| | `i4_pos_switch` | not used in the code |
| MM_1 | `i1_pos_switch` | turntable at `initial` |
| | `i2_pos_switch` | turntable at the mill |
| | `i3_pos_switch` | turntable at `ejection` |
| | `i4_light_barrier` | on the milling machine's belt towards the sorting machine (exact spot unverified) |
| SM_1 | `i1_light_barrier` | sorting-machine belt entry |
| | `i2_color_sensor` | brightness under the colour sensor |
| | `i3_light_barrier` | after colour detection |
| | `i6/i7/i8_light_barrier` | sink 1 / 2 / 3 occupied |
| HBW_1 | `i1…i4_light_barrier`, `i5…i8_pos_switch`, `current_stock` | not needed for the first layout |

## Known quirks

- **WT_1 always reports `ready`.** The controller writes WT's task into the oven's fields, so the oven's `current_task` shows the transport.
- The compressor sits on MM_1 (`o8_compressor`) and is shared with OV and WT. It turns on in MM_1's message during oven and WT tasks.
- The VGR's `i4_light_barrier` is read through a counter input. It is still 0/1.

## Simulator

The layout file `src/main/resources/layouts/fischertechnik.yaml` declares the same messages (`events:`); the generic event publisher sends them for the running Fischertechnik simulation.

- Broker: `simulation.events.mqtt.broker-url`, default `tcp://localhost:1883`; in `docker compose` the bundled Mosquitto. Optional `username` and `password`.
- Topics `FTFactory/VGR_1`, `OV_1`, `WT_1`, `MM_1`, `SM_1`, every 500 ms (`every:` in the layout). HBW_1 is not published.
- Messages are for the most recently started running Fischertechnik simulation; nothing is published while none runs.
- Without a reachable broker the simulator keeps running and retries every 10 s.
- Sensor faults switched in the UI change the published values too (e.g. a stuck light barrier).

Differences from the physical factory:

- WT_1 reports its own status, not always `ready`.
- Axis and position switches, valves and the compressor report fixed idle values; motor speeds are 512 while the machine works.
- `current_task` and `current_sub_task` are empty and `current_task_duration` is 0.
- `SM_1.i2_color_sensor` is 1766 without a workpiece at the colour sensor, and 1000 / 1350 / 1560 for a white / red / blue workpiece there.
