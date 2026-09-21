#!/usr/bin/env bash
# Build then launch the Spring Boot app in the background under caffeinate so
# the server keeps running when the laptop lid closes or the display sleeps.
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p data
LOG="./server.log"
PID="./server.pid"

if [[ -f "$PID" ]] && kill -0 "$(cat "$PID")" 2>/dev/null; then
  echo "Already running (pid $(cat "$PID")). Logs: $LOG"
  exit 0
fi

echo "Building..."
mvn -q -DskipTests package

JAR="$(ls -1 target/checkout-rewards-service-*.jar | head -n1)"
if [[ -z "$JAR" ]]; then
  echo "Build produced no jar." >&2
  exit 1
fi

echo "Launching under caffeinate (survives sleep). Logs: $LOG"
# -i prevents idle system sleep, -s prevents system sleep even on battery,
# -m prevents disk from sleeping. nohup detaches from the terminal.
nohup caffeinate -ims java -jar "$JAR" >"$LOG" 2>&1 &
echo $! > "$PID"
sleep 2
echo "PID $(cat "$PID"). Try: curl -s http://localhost:8080/products | jq ."
