#!/usr/bin/env bash
# Calls one factory command and appends request, status, duration and body to http.log.
# Usage: [API_BASE=http://192.168.0.21:5000] ./call.sh "/ov/burn?machine=ov_1" [label]
set -euo pipefail

BASE="${API_BASE:-http://192.168.0.21:5000}"
PATH_QUERY="$1"
LABEL="${2:-normal}"
OUT="${OUT_DIR:-.}/http.log"
BODY="$(mktemp)"

STARTED="$(date '+%Y-%m-%dT%H:%M:%S%z')"
RESULT="$(curl -s -o "$BODY" -w '%{http_code} %{time_total}' "$BASE$PATH_QUERY" || echo "000 0")"
STATUS="${RESULT%% *}"
SECONDS_TAKEN="${RESULT##* }"

{
  echo "=== $STARTED [$LABEL] GET $PATH_QUERY"
  echo "status: $STATUS"
  echo "duration_s: $SECONDS_TAKEN"
  echo "body:"
  cat "$BODY"
  echo
} | tee -a "$OUT"
rm -f "$BODY"
