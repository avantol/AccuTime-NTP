#!/bin/bash
# Double-click / terminal launcher for the AccuTime GPS clock sync.
# Finds accutime-sync.py next to this script, runs it as root, and pauses so
# you can read the result (and type the sudo password if prompted).
#
# Any arguments you pass are forwarded, e.g.:
#   ./launch-accutime.sh --loop --interval 60
# With no args it auto-detects the phone (the hotspot gateway).

# Pause only when running interactively (a terminal). Prevents hanging if this
# is ever run headless (e.g. the no-terminal fallback in run-accutime-gui.sh).
pause() { [ -t 0 ] && read -r -p "Press Enter to close."; }

# Directory this script lives in (works no matter where it's launched from).
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"

# Prefer python3; fall back to python (the script is 2/3 compatible).
PY="$(command -v python3 || command -v python)"
if [ -z "$PY" ]; then
    echo "ERROR: no python found on this PC."
    pause
    exit 1
fi

echo "Syncing clock from AccuTime phone..."
sudo "$PY" "$DIR/accutime-sync.py" "$@"
STATUS=$?

echo
if [ "$STATUS" -eq 0 ]; then
    echo "Done."
else
    echo "Sync failed (exit $STATUS). Check the phone hotspot + app, then retry."
fi
pause
