#!/usr/bin/env python3
"""Rearranges stones with the lab's Dobot Magician, real or simulated.

The application only uses the Dobot's own HTTP API (/move_to, /pick_up_and_transport), so the same
code runs against the physical arm (--url http://<lab computer>:5000) and the simulator
(--url http://localhost:8080). Steps: move the arm over the grid once, then carry each stone from
the near row (pos1, pos3, pos5, pos7) to the far row (pos2, pos4, pos6, pos8), and back again.
Uses only the Python standard library.

--setup prepares the simulation that is open in the UI: the running Dobot simulation (or
--simulation <id> if several run), started if needed, with a stone on each free near-row position.
Only if none runs, it creates one. It never stops a simulation. It uses the simulator's control
API, which the real arm does not have: there, a person puts the stones down.

Usage: python3 rearrange_stones.py [--url http://localhost:8080] [--setup] [--simulation <id>] [--no-back]
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

LAYOUT = "dobot"
NEAR_ROW = ["pos1", "pos3", "pos5", "pos7"]
FAR_ROW = ["pos2", "pos4", "pos6", "pos8"]
COLORS = ["red", "green", "blue", "yellow"]


def call(method, url, body=None, timeout=600):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        try:
            return error.code, json.loads(text)
        except ValueError:
            return error.code, {"error": text.strip()[:300]}


def dobot(base, path):
    """One Dobot call; blocks until the arm is done, like the physical API."""
    started = time.monotonic()
    status, body = call("GET", f"{base}{path}")
    if status != 200:
        print(f"  {path} -> {status} {(body or {}).get('error', '')}")
        sys.exit(1)
    print(f"  {path:50} {time.monotonic() - started:5.1f} s  {body['message']}")
    return body


def require(status, body, what):
    if status >= 300:
        print(f"Setup failed while {what}: {status} {(body or {}).get('error', '')}")
        sys.exit(1)


def setup_simulator(base, simulation_id=None):
    """Prepares the simulation that is open in the UI: the running Dobot simulation (or the one given
    with --simulation). Creates and starts one only if none is running. Never stops one."""
    _, simulations = call("GET", f"{base}/simulations")
    running = [s for s in simulations or [] if s["layoutId"] == LAYOUT and s["status"] == "RUNNING"]
    if simulation_id:
        chosen = next((s for s in simulations or [] if s["id"].startswith(simulation_id)), None)
        if chosen is None:
            print(f"No simulation {simulation_id}")
            sys.exit(1)
    elif len(running) == 1:
        chosen = running[0]
    elif len(running) > 1:
        # The Dobot API drives the most recently started one; the list does not say which that is.
        print("Several Dobot simulations are running. Stop the ones you don't use in the UI,")
        print("or choose one with --simulation <id>:")
        for s in running:
            print(f"  {s['id']}")
        sys.exit(1)
    else:
        status, created = call("POST", f"{base}/simulations", {"layoutId": LAYOUT})
        require(status, created, "creating a simulation")
        chosen = {"id": created["id"], "status": "CREATED", "speed": None, "marking": {}}
        print(f"No simulation was running; created {created['id']}")
    simulation = f"{base}/simulations/{chosen['id']}"
    if chosen["status"] != "RUNNING":
        require(*call("POST", f"{simulation}/start"), "starting the simulation")
    if chosen.get("speed") is None:
        require(*call("PUT", f"{simulation}/speed", {"factor": 1}), "starting real time")
    marking = chosen.get("marking", {})
    for position, color in zip(NEAR_ROW, COLORS):
        if marking.get(position):
            print(f"Simulation {chosen['id']}: a stone is already on {position}")
            continue
        status, body = call("POST", f"{simulation}/tokens", {"place": position, "attributes": {"color": color}})
        require(status, body, f"putting a stone on {position}")
        print(f"Simulation {chosen['id']}: {color} stone put on {position}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080", help="Dobot API or simulator base URL")
    parser.add_argument("--setup", action="store_true",
                        help="simulator only: use the running simulation (the one open in the UI), start it if "
                             "needed, put a stone on each free near-row position")
    parser.add_argument("--simulation", help="with --setup: id (or its first characters) of the simulation to use")
    parser.add_argument("--no-back", action="store_true", help="leave the stones in the far row")
    args = parser.parse_args()

    if args.setup:
        setup_simulator(args.url, args.simulation)
    print(f"Rearranging stones on {args.url}")
    dobot(args.url, "/move_to?start=pos1&end=pos8")
    for near, far in zip(NEAR_ROW, FAR_ROW):
        dobot(args.url, f"/pick_up_and_transport?start={near}&end={far}")
    if not args.no_back:
        for near, far in zip(NEAR_ROW, FAR_ROW):
            dobot(args.url, f"/pick_up_and_transport?start={far}&end={near}")
    print("Done")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except urllib.error.URLError as error:
        print(f"Cannot reach the Dobot: {error.reason}")
        sys.exit(1)
