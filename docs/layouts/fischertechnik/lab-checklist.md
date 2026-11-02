# Lab checklist – Fischertechnik Factory 4.0

What is still needed from the lab. Most questions were answered by the factory source, see [http-api.md](http-api.md) and [mqtt.md](mqtt.md). Recording scripts: [tools/lab-recording](../../../tools/lab-recording/README.md).

## Done

- [x] Python source of the API server and controllers (`smart-factory-python`)
- [x] MQTT payload per station
- [x] Valid positions and parameter values
- [x] Busy behaviour: requests queue, never rejected
- [x] Light-barrier meaning: raw `0` = interrupted
- [x] Meaning of `time` (oven: × 2.4 s; mill: seconds)
- [x] Colour values: `white`, `red`, `blue`, `none`
- [x] Sorting machine gets the workpiece from the milling machine's belt, pushed by the milling machine
- [x] Rough durations (2021 production log)

## Still to collect

- [ ] MQTT recording of one full run of the 6-step process (`record-mqtt.sh`). Needed for the timing of sensor changes.
- [ ] HTTP recording of the same run with durations (`run-process.sh`)
- [ ] MQTT broker host, port and login used in the lab now
- [ ] Photo of the factory from directly above, with all stations in the frame. Used to place stations and positions in the UI's floor view.

## Optional

- [ ] One "no workpiece" run of `/vgr/pick_up_and_transport` to confirm it returns 200
- [ ] Failures seen in practice (dropped workpiece, colour misread) and how they show in MQTT
