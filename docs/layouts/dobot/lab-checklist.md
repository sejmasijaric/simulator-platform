# Lab checklist – Dobot + conveyor

What is still needed from the lab. The arm side is modelled from `dobot-main`, see [http-api.md](http-api.md).

## Done

- [x] Python source of the Dobot API (`dobot-main`: `app.py`, `dobot.py`)
- [x] Endpoints, parameters, responses and errors
- [x] Stone positions, home pose, factory route
- [x] Kafka message format (topic `Dobot`)
- [x] Picture for the UI (`web/public/layouts/dobot-conveyer.png`, product photo)

## Arm

- [ ] Is `dobot-main` the code running in the lab now? Which URL and port do applications call?
- [ ] Is there one arm or two? The picture shows two.
- [ ] Time for each call: `move_to pos1 → pos8`, `pick_up_and_transport pos1 → factory`, `run_xml` with `test.playback`. Needed for the move speed (now 100 mm/s + 0.2 s) and calibration (now 20 s).
- [ ] Does the HTTP response come only after the arm has stopped?
- [ ] Is anything consuming the Kafka topic `Dobot`? Broker address today?
- [ ] What happens on an unreachable pose (alarm, error, no response)?
- [ ] Confirm the quirks: `pick_up_and_transport?start=factory` picks nothing; `run_xml` needs a `file_path` part.

## Conveyor

- [ ] How is it controlled: Dobot's stepper port (`conveyor_belt` in `pydobotplus`), DobotStudio, or something else? Any code?
- [ ] Length, speed, direction; does it run for a time, a distance, or until a sensor fires?
- [ ] Sensors: infrared / colour sensor kit? Where on the belt, and how are they read?
- [ ] Arm coordinates of the belt's pick-up and drop-off points.
- [ ] How the conveyor relates to the stone positions and the factory: what is the process?

## Optional

- [ ] Photo from directly above, with the stone grid and factory handover in the frame
- [ ] Failures seen in practice (stone not gripped, dropped) and how they show
