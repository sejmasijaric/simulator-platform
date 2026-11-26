# Dobot: rearrange stones

`rearrange_stones.py` is an application for the lab's Dobot Magician. It moves the arm over the stone grid once, carries each stone from the near row (`pos1`, `pos3`, `pos5`, `pos7`) to the far row (`pos2`, `pos4`, `pos6`, `pos8`) and back again. It uses only the Dobot's own HTTP API (`/move_to`, `/pick_up_and_transport`), so the same code runs against the simulator and the physical arm. Only the base URL differs.

| | Simulator | Physical arm |
|---|---|---|
| HTTP API | `http://localhost:8080` | `http://<lab computer>:5000` (to be confirmed, see [lab-checklist.md](../../docs/layouts/dobot/lab-checklist.md)) |
| Stones on the near row | placed in the UI or with `--setup` | put down by hand |

## Simulator

```sh
docker compose up --build                                  # terminal 1, UI on http://localhost:8080
python3 applications/dobot/rearrange_stones.py --setup     # terminal 2
```

Open the Dobot simulation in the UI first to watch the stones move: `--setup` uses that one and puts a stone on each free near-row position (it creates a simulation only if none is running, and never stops one; with several running, choose with `--simulation <id>`). Each call prints how long it took and the arm's `message`. The whole run takes about 50 s.

## Physical arm

```sh
python3 applications/dobot/rearrange_stones.py --url http://<lab computer>:5000
```

Put stones on `pos1`, `pos3`, `pos5` and `pos7` first.

## Options

- `--no-back` leaves the stones in the far row.

Switch on a fault in the simulator to see how the application behaves when the arm does not do what it reports, e.g. `TOKEN_LOSS` on transition `dobot_pick` (the suction does not grip): the API still answers success, as app.py does, and only the UI shows the stone missing.
