# Demo process

`demo_process.py` plays an application against the demo layout, so the UI can be tried without hardware or Camunda. It sends commands with origin `application` and waits for each to finish.

```sh
mvn spring-boot:run              # terminal 1
python3 tools/demo/demo_process.py   # terminal 2
```

Open http://localhost:8080, open the running demo simulation and watch. Switch on a fault (e.g. `JAMMED` on `C`) to see the process get stuck the way it would on hardware.
