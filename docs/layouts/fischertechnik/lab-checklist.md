# Lab checklist – Fischertechnik Factory 4.0

What to collect in the lab to build the Fischertechnik layout. Known commands: [http-api.md](http-api.md). Recording script: [tools/lab-recording](../../../tools/lab-recording/README.md).

## 1. Files to bring back

- [ ] Python source of the factory API server (complete file, plus any modules it imports)
- [ ] MQTT payload document
- [ ] MQTT recording of a full production run (`mqtt.log`)
- [ ] HTTP request/response recordings with durations (`http.log`)
- [ ] Top-down photo of the factory

## 2. Setup

- [ ] API base URL (expected `http://192.168.0.21:5000`)
- [ ] MQTT broker host, port, username/password needed?
- [ ] MQTT topic names (one per machine?)
- [ ] Laptop has `curl` and `mosquitto_sub` (`brew install mosquitto`)

## 3. Layout

- [ ] Stations in operation: vacuum gripper, oven, workstation transport, milling machine, sorting machine, others (high-bay warehouse, input/output, environment sensor, camera)?
- [ ] All workpiece positions, with the name the API uses for each
- [ ] Which machine moves a workpiece between which positions
- [ ] Sensors: name in the payload, position it watches, value meaning (light barrier: 0 = interrupted or free?)
- [ ] Workpiece colours and other attributes (NFC id?)
- [ ] Initial state: where workpieces are at start

## 4. Per command

Run each command once in the normal case. Record request, response, HTTP status and duration.

| Command | Normal | Busy | No workpiece | Invalid value |
|---|---|---|---|---|
| `/vgr/pick_up_and_transport` | [ ] | [ ] | [ ] | [ ] |
| `/ov/burn` | [ ] | [ ] | [ ] | |
| `/wt/pick_up_and_transport` | [ ] | [ ] | [ ] | [ ] |
| `/mm/mill` | [ ] | [ ] | [ ] | [ ] |
| `/sm/detect_color` | [ ] | | [ ] | |
| `/sm/sort` | [ ] | [ ] | [ ] | [ ] |

- **Busy:** send the same command again while the first runs (second terminal).
- **No workpiece:** command with an empty `start` position.
- **Invalid value:** e.g. `end=foo`.

For each command also note:
- [ ] Does the call block until the task is done?
- [ ] Which sensors change during the task, and when?
- [ ] Meaning of `time` (`/ov/burn`, `/mm/mill`)
- [ ] Colour values returned by `/sm/detect_color`
- [ ] Does the sorting machine pick up the workpiece from the milling machine's ejection by itself?

Only the "Normal" column is required. Skip error cases that could damage the factory or leave it in a state you cannot recover.

## 5. Failures

- [ ] Failures that happen in practice (workpiece drops, colour misread, sensor does not trigger, machine hangs)
- [ ] How each one shows in the HTTP response and MQTT messages
