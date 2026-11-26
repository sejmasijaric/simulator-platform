# ADR 0016: What the Fischertechnik factory simulation covers

Status: Accepted (2026-11-26).

## Context
The lab's Fischertechnik Factory 4.0 is driven by `smart-factory-python`: a Flask server (`app.py`, port 5000) that forwards each call to one controller per TXT (`hardware/generic/*_txts.py`), which moves the motors and publishes MQTT. Applications (e.g. the Camunda process, `applications/fischertechnik/lab_process.py`) talk only to that HTTP API and those MQTT topics. The simulator has to stand in for this factory so that an unchanged application runs against it (ADR 0009, 0015).

The vendor's booklet (*Training Factory Industry 4.0*, activity booklet) describes the standard 24V PLC version. The lab's factory runs the same hardware with its own controllers, station names (VGR, OV, WT, MM, SM, HBW instead of VGR, MPO, SLD, HBW) and input numbering, so the booklet is a source for which sensors physically exist and where, not for the interface.

Sources, in order of authority:
1. `smart-factory-python` source (read 2026-11-02 and 2026-11-26): endpoints, parameters, responses, errors, motors, task texts.
2. Recorded MQTT messages per station (2026-04-01).
3. Production log of March 2021 (older setup): durations, until the lab recordings are in.
4. Vendor booklet and a photo of the factory: sensor inventory and positions for the UI.

## Decision
Simulate the factory's own interface as the lab's applications use it, faithfully, and add nothing that the physical factory does not have. The layout is `src/main/resources/layouts/fischertechnik.yaml` (ADR 0011); nothing about the factory is coded in Java.

**Simulated** (same paths, parameters, responses and error answers as `app.py`):

| Part | Endpoints / messages |
|---|---|
| Commands | `/vgr/pick_up_and_transport`, `/ov/burn`, `/wt/pick_up_and_transport`, `/mm/mill`, `/sm/detect_color`, `/sm/sort`, `/process/unload_from_hbw_container` |
| Queries | `status_of_light_barrier` (vgr, ov, mm, sm), `state_of_machine` (vgr, ov, wt, mm, sm) |
| Motor speeds | `get_motor_speed`, `set_motor_speed`, `reset_all_motor_speeds` for vgr (motors 1–3), ov (1), wt (2), mm (1–3), sm (1); ADR 0017 |
| MQTT | `FTFactory/VGR_1`, `OV_1`, `WT_1`, `MM_1`, `SM_1` every 500 ms, including `current_task` and `current_sub_task` |

Behaviour reproduced from the source: calls block until the machine is done; a busy machine queues further requests instead of refusing them; controllers do not check whether a workpiece is there (an empty move succeeds); the oven burns `time × 2.4 s`; `detect_color` moves the workpiece, so a following colour-based `sort` reads `none`; colour thresholds and raw brightness; motor speeds are clamped to −512…512 and stretch the motor-driven part of a step (512 / speed); the transport (WT) writes its task into the oven's message fields; the warehouse is out of service, as in the lab (November 2026), so an unload never finishes, like the physical call that polls the warehouse's light barrier without a timeout.

**Not simulated**, although the physical factory offers them, because no application of the lab uses them, or because they need the warehouse, which does not work:
- every machine: `calibrate`;
- `check_position` (vgr, wt, mm); `/vgr/move_to`, `/vgr/stop_vacuum_suction`, `/vgr/read_color`; `/wt/move_to`; `/mm/move_from_to`, `/mm/transport_from_to`;
- the warehouse: `/hbw/*` (store, unload, change_buckets, get_workpiece_by_color, wait_until_light_barrier_is_interrupted, state_of_machine, status_of_light_barrier, motor speeds, get_amount_of_stored_workpieces, get_slot_number_of_workpiece_by_color) and the `HBW_1` messages;
- the composite processes `/process/store`, `/process/produce`, `/process/store_in_hbw_container`.

Each can be added to the YAML when an application needs it; `docs/layouts/fischertechnik/http-api.md` records their parameters and answers from the source.

## Consequences
- The lab's production process and the example application run unchanged against the simulator (`LayoutHttpApiTest`, `MotorSpeedAndTasksTest`).
- An application that calls an endpoint from the "not simulated" list gets a 404 from the simulator, where the physical factory would answer. This is deliberate and documented.
- Known differences, to be calibrated or confirmed in the lab:
  - durations are starting values from 2021; the gripper's move takes the same time for every start/end pair, while the physical one depends on the distance;
  - motor speed is assumed to scale linearly (512 / speed) and a negative speed to move at the same pace;
  - `/sm/sort` with `start=sm_cb` or `predefined_ejection_location=corner` answers 400; sorting by colour with no detectable colour waits instead of answering 400; `use_nfc` is ignored (the physical machine takes 1 s longer);
  - a move to an occupied position waits until it is free; the physical gripper sets the workpiece down anyway;
  - state fields: physically WT_1 always reports `ready` and OV_1 reports `not ready` during a transport; the simulator derives each station's state from its own machine;
  - fixed MQTT fields: positions, valves, compressor, axis switches and `current_task_duration`; sub-task texts follow the steps of the net, which are coarser than the controllers' own sub-steps.
- Open for the next lab visit: durations per command (several runs, the gripper per start/end pair), one move at speed 512 and 256, an MQTT recording during a run, and one warehouse unload to confirm that it hangs.
