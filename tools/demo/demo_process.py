#!/usr/bin/env python3
"""Plays an application against the demo layout until stopped (Ctrl+C).

Stands in for a real process (e.g. Camunda + hardware adapter) so the UI can be tried without
hardware. Each round: transport IN -> OUT, run the conveyor OUT -> END, take the workpiece out
of END. Commands are sent with origin "application" and wait for completion like blocking
hardware calls. Uses only the Python standard library.

Usage: python3 demo_process.py [--url http://localhost:8080] [--simulation <id>] [--pause 2]
"""
import argparse
import json
import random
import time
import urllib.error
import urllib.request


def call(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            text = response.read().decode()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        return error.code, json.loads(text) if text else None


def find_or_create(base, simulation_id):
    if simulation_id:
        return simulation_id
    _, simulations = call("GET", f"{base}/simulations")
    for simulation in simulations or []:
        if simulation["definitionId"] == "demo":
            return simulation["id"]
    _, created = call("POST", f"{base}/simulations", {"definitionId": "demo"})
    call("POST", f"{base}/simulations/{created['id']}/start")
    call("PUT", f"{base}/simulations/{created['id']}/real-time", {"speedFactor": 1})
    return created["id"]


def command(sim, component, capability, parameters=None):
    status, body = call("POST", f"{sim}/components/{component}/actions?await=true&timeout=PT120S",
                        {"capability": capability, "parameters": parameters or {}, "origin": "application"})
    print(f"  {component}.{capability} -> {status} {body.get('status') if body else ''}")
    return status == 200


def entities_at(sim, component):
    _, snapshot = call("GET", sim)
    return [e for e in snapshot["entities"] if e["locationComponentId"] == component]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--simulation")
    parser.add_argument("--pause", type=float, default=2.0, help="seconds between rounds")
    args = parser.parse_args()

    sim = f"{args.url}/simulations/{find_or_create(args.url, args.simulation)}"
    print(f"Driving {sim}")
    round_number = 0
    while True:
        round_number += 1
        print(f"Round {round_number}")
        if not entities_at(sim, "IN") and not entities_at(sim, "OUT"):
            color = random.choice(["red", "white", "blue"])
            call("POST", f"{sim}/entities", {"type": "workpiece", "attributes": {"color": color}, "componentId": "IN"})
            print(f"  placed a {color} workpiece at IN")
        if entities_at(sim, "IN") and not entities_at(sim, "OUT"):
            command(sim, "T", "transport", {"from": "IN", "to": "OUT"})
        if entities_at(sim, "OUT") and not entities_at(sim, "END"):
            command(sim, "C", "run")
        for entity in entities_at(sim, "END"):
            time.sleep(args.pause)
            call("DELETE", f"{sim}/entities/{entity['id']}")
            print(f"  took {entity['id']} out of END")
        time.sleep(args.pause)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
