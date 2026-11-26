#!/usr/bin/env python3
"""Runs the lab's production process on the Fischertechnik factory, real or simulated.

The application only uses the factory's own HTTP API, so the same code runs against the physical
factory (--url http://192.168.0.21:5000) and the simulator (--url http://localhost:8080). Steps:
gripper delivery station -> oven, burn, workstation transport -> milling machine, mill and eject
onto the sorting line, detect the colour, sort into a sink. Light barriers are read between the
steps. Uses only the Python standard library.

--setup prepares the simulation that is open in the UI: the running Fischertechnik simulation
(or --simulation <id> if several run), started if needed, with a workpiece on the delivery station.
Only if none runs, it creates one. It never stops a simulation. It uses the simulator's control
API, which the real factory does not have: there, a person puts the workpiece in.

Usage: python3 lab_process.py [--url http://localhost:8080] [--setup] [--simulation <id>] [--color red] [--sink sink_1]
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request


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
            return error.code, {"error": text[:200]}


def factory(base, path):
    """One factory call; blocks until the machine is done, like the physical API."""
    status, body = call("GET", f"{base}{path}")
    if status != 200:
        print(f"  {path} -> {status} {body.get('error') if body else ''}")
        sys.exit(1)
    attributes = f" {body['attributes']}" if body.get("attributes") else ""
    print(f"  {path.split('?')[0]:28} {body['process_time']}{attributes}")
    return body


def light_barrier(base, machine, number):
    _, body = call("GET", f"{base}/{machine}/status_of_light_barrier?machine={machine}_1&lb={number}")
    return "interrupted" if body and body["attributes"][0]["interrupted"] else "free"


def require(status, body, what):
    if status >= 300:
        print(f"Setup failed while {what}: {status} {(body or {}).get('error', '')}")
        sys.exit(1)


def setup_simulator(base, color, simulation_id=None):
    """Prepares the simulation that is open in the UI: the running Fischertechnik simulation (or the
    one given with --simulation). Creates and starts one only if none is running. Never stops one."""
    _, simulations = call("GET", f"{base}/simulations")
    running = [s for s in simulations or [] if s["layoutId"] == "fischertechnik" and s["status"] == "RUNNING"]
    if simulation_id:
        chosen = next((s for s in simulations or [] if s["id"].startswith(simulation_id)), None)
        if chosen is None:
            print(f"No simulation {simulation_id}")
            sys.exit(1)
    elif len(running) == 1:
        chosen = running[0]
    elif len(running) > 1:
        # The factory API drives the most recently started one; the list does not say which that is.
        print("Several Fischertechnik simulations are running. Stop the ones you don't use in the UI,")
        print("or choose one with --simulation <id>:")
        for s in running:
            print(f"  {s['id']}")
        sys.exit(1)
    else:
        status, created = call("POST", f"{base}/simulations", {"layoutId": "fischertechnik"})
        require(status, created, "creating a simulation")
        chosen = {"id": created["id"], "status": "CREATED", "speed": None, "marking": {}}
        print(f"No simulation was running; created {created['id']}")
    simulation = f"{base}/simulations/{chosen['id']}"
    if chosen["status"] != "RUNNING":
        require(*call("POST", f"{simulation}/start"), "starting the simulation")
    if chosen.get("speed") is None:
        require(*call("PUT", f"{simulation}/speed", {"factor": 1}), "starting real time")
    if chosen.get("marking", {}).get("delivery_pick_up_station"):
        print(f"Simulation {chosen['id']}: a workpiece is already on the delivery station")
    else:
        status, body = call("POST", f"{simulation}/tokens",
                            {"place": "delivery_pick_up_station", "attributes": {"color": color}})
        require(status, body, "putting the workpiece on the delivery station")
        print(f"Simulation {chosen['id']}: {color} workpiece put on the delivery station")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080", help="factory or simulator base URL")
    parser.add_argument("--setup", action="store_true",
                        help="simulator only: use the running simulation (the one open in the UI), start it if needed, "
                             "put a workpiece on the delivery station")
    parser.add_argument("--simulation", help="with --setup: id (or its first characters) of the simulation to use")
    parser.add_argument("--color", default="red", help="workpiece colour for --setup")
    parser.add_argument("--sink", default="sink_1", help="sink_1..3, or none to sort by colour")
    args = parser.parse_args()

    if args.setup:
        setup_simulator(args.url, args.color, args.simulation)
    print(f"Running the lab process on {args.url}")
    print(f"  delivery station: {light_barrier(args.url, 'vgr', 7)}")
    factory(args.url, "/vgr/pick_up_and_transport?machine=vgr_1&start=delivery_pick_up_station&end=oven")
    print(f"  oven: {light_barrier(args.url, 'ov', 5)}")
    factory(args.url, "/ov/burn?machine=ov_1&time=2")
    factory(args.url, "/wt/pick_up_and_transport?machine=wt_1&start=oven&end=milling_machine")
    factory(args.url, "/mm/mill?machine=mm_1&time=10&start=initial&end=ejection")
    print(f"  sorting line entry: {light_barrier(args.url, 'sm', 1)}")
    if args.sink != "none":
        # Detecting first moves the workpiece past the sensor; sorting by colour afterwards would read "none".
        factory(args.url, "/sm/detect_color?machine=sm_1")
    factory(args.url, f"/sm/sort?machine=sm_1&start=initial&predefined_ejection_location={args.sink}")
    sinks = {"sink_1": 6, "sink_2": 7, "sink_3": 8}
    print("  sinks: " + ", ".join(f"{name} {light_barrier(args.url, 'sm', lb)}" for name, lb in sinks.items()))
    print("Done")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except urllib.error.URLError as error:
        print(f"Cannot reach the factory: {error.reason}")
        sys.exit(1)
