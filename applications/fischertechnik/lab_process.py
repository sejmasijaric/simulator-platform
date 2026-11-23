#!/usr/bin/env python3
"""Runs the lab's production process on the Fischertechnik factory, real or simulated.

The application only uses the factory's own HTTP API, so the same code runs against the physical
factory (--url http://192.168.0.21:5000) and the simulator (--url http://localhost:8080). Steps:
gripper delivery station -> oven, burn, workstation transport -> milling machine, mill and eject
onto the sorting line, detect the colour, sort into a sink. Light barriers are read between the
steps. Uses only the Python standard library.

--setup prepares the simulator (create and start a Fischertechnik simulation, place a workpiece
on the delivery station). It uses the simulator's control API, which the real factory does not
have: there, a person puts the workpiece in.

Usage: python3 lab_process.py [--url http://localhost:8080] [--setup] [--color red] [--sink sink_1]
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


def setup_simulator(base, color):
    _, simulations = call("GET", f"{base}/simulations")
    for simulation in simulations or []:
        if simulation["layoutId"] == "fischertechnik" and simulation["status"] == "RUNNING":
            call("POST", f"{base}/simulations/{simulation['id']}/stop")
    _, created = call("POST", f"{base}/simulations", {"layoutId": "fischertechnik"})
    simulation = f"{base}/simulations/{created['id']}"
    call("POST", f"{simulation}/start")
    call("PUT", f"{simulation}/speed", {"factor": 1})
    call("POST", f"{simulation}/tokens", {"place": "delivery_pick_up_station", "attributes": {"color": color}})
    print(f"Simulator ready: {created['id']}, {color} workpiece on the delivery station")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080", help="factory or simulator base URL")
    parser.add_argument("--setup", action="store_true", help="prepare the simulator first (simulator only)")
    parser.add_argument("--color", default="red", help="workpiece colour for --setup")
    parser.add_argument("--sink", default="sink_1", help="sink_1..3, or none to sort by colour")
    args = parser.parse_args()

    if args.setup:
        setup_simulator(args.url, args.color)
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
