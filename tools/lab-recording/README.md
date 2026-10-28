# Lab recording

Records the physical factory's HTTP responses and MQTT messages. The recordings are the reference for the Fischertechnik layout and its contract tests. Checklist: [lab-checklist.md](../../docs/layouts/fischertechnik/lab-checklist.md).

Requirements: `curl`, `mosquitto_sub` (`brew install mosquitto`), laptop in the lab network.

## Steps

1. Terminal 1, start the MQTT recording and keep it running:
   ```sh
   MQTT_HOST=192.168.0.21 ./record-mqtt.sh
   ```
2. Terminal 2, place a workpiece at the delivery pick-up station and run the full process:
   ```sh
   ./run-process.sh
   ```
3. Single commands, e.g. error cases. The second argument is a label in the log:
   ```sh
   ./call.sh "/vgr/pick_up_and_transport?machine=vgr_1&start=delivery_pick_up_station&end=foo" invalid-end
   ```
4. Stop the MQTT recording with Ctrl+C. Bring back `mqtt.log` and `http.log`.

## Configuration

| Variable | Default |
|---|---|
| `API_BASE` | `http://192.168.0.21:5000` |
| `MQTT_HOST` | `192.168.0.21` |
| `MQTT_PORT` | `1883` |
| `MQTT_USER`, `MQTT_PASSWORD` | none |
| `OUT_DIR` | current directory |
| `SINK` (`run-process.sh`) | `sink_1` |
