#!/usr/bin/env bash
# Records all MQTT messages with timestamps to mqtt.log. Stop with Ctrl+C.
# Usage: MQTT_HOST=192.168.0.21 [MQTT_PORT=1883] [MQTT_USER=..] [MQTT_PASSWORD=..] ./record-mqtt.sh
set -euo pipefail

HOST="${MQTT_HOST:-192.168.0.21}"
PORT="${MQTT_PORT:-1883}"
OUT="${OUT_DIR:-.}/mqtt.log"

AUTH=()
if [[ -n "${MQTT_USER:-}" ]]; then
  AUTH=(-u "$MQTT_USER" -P "${MQTT_PASSWORD:-}")
fi

echo "Recording mqtt://$HOST:$PORT (all topics) to $OUT"
mosquitto_sub -h "$HOST" -p "$PORT" "${AUTH[@]+"${AUTH[@]}"}" -t '#' -v -F '%I %t %p' | tee -a "$OUT"
