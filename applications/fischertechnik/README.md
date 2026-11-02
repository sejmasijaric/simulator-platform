# Fischertechnik lab process

`lab_process.py` is an application for the Fischertechnik Factory 4.0. It runs the lab's production process (gripper → oven → transport → milling → colour detection → sorting) using only the factory's own HTTP API, so the same code runs against the simulator and the physical factory. Only the base URL differs.

| | Simulator | Physical factory |
|---|---|---|
| HTTP API | `http://localhost:8080` | `http://192.168.0.21:5000` |
| MQTT status messages | `tcp://localhost:1883` (`docker compose up`) | lab broker |
| Workpiece on the delivery station | placed in the UI or with `--setup` | put in by hand |

## Simulator

```sh
mvn spring-boot:run                                   # terminal 1, UI on http://localhost:8080
python3 applications/fischertechnik/lab_process.py --setup   # terminal 2
```

Open http://localhost:8080 and the running Fischertechnik simulation to watch the workpiece move. Each step prints the factory's `process_time`; the light barriers are read between steps.

To watch the status messages the factory publishes, start the simulator with `docker compose up` and run `mosquitto_sub -h localhost -t 'FTFactory/#' -v`.

## Physical factory

```sh
python3 applications/fischertechnik/lab_process.py --url http://192.168.0.21:5000
```

Put a workpiece on the delivery station first.

## Options

- `--sink sink_1|sink_2|sink_3` sorts into a fixed sink (default `sink_1`); `--sink none` sorts by colour.
- `--color white|red|blue` sets the workpiece colour for `--setup`.

Switch on a fault in the simulator (Fault mode, e.g. `MISSES_WORKPIECE` on `vgr_1`) to see how the application behaves when a machine does not do what it reports.
