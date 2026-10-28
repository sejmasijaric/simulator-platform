#!/usr/bin/env bash
# Runs the known production sequence once. Place a workpiece at the delivery pick-up station first.
# Usage: [API_BASE=...] [SINK=sink_1] ./run-process.sh
set -euo pipefail
cd "$(dirname "$0")"

SINK="${SINK:-sink_1}"

./call.sh "/vgr/pick_up_and_transport?machine=vgr_1&start=delivery_pick_up_station&end=oven"
./call.sh "/ov/burn?machine=ov_1"
./call.sh "/wt/pick_up_and_transport?machine=wt_1&start=oven&end=milling_machine"
./call.sh "/mm/mill?machine=mm_1&time=10&start=initial&end=ejection"
./call.sh "/sm/detect_color?machine=sm_1"
./call.sh "/sm/sort?machine=sm_1&start=initial&predefined_ejection_location=$SINK"
