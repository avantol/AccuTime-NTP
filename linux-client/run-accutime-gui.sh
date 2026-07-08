#!/bin/bash
# Robust double-click entry point for Linux Lite / XFCE.
#
# Why this exists: a .desktop with Terminal=true relies on XFCE having a default
# TerminalEmulator configured (via exo-open). On trimmed installs that isn't set,
# so the launcher does NOTHING with no error. This script finds an installed
# terminal itself and runs the sync inside it — point the .desktop at THIS file
# with Terminal=false.

DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
TARGET="$DIR/launch-accutime.sh"

run_in() {
    # Terminals differ in how they take a command to run; handle the common ones.
    case "$1" in
        xfce4-terminal) exec "$1" --title="AccuTime Sync" -x bash "$TARGET" ;;
        gnome-terminal) exec "$1" -- bash "$TARGET" ;;
        *)              exec "$1" -e bash "$TARGET" ;;  # xterm, lxterminal, konsole, mate-terminal, x-terminal-emulator
    esac
}

for t in xfce4-terminal x-terminal-emulator lxterminal mate-terminal gnome-terminal konsole xterm; do
    if command -v "$t" >/dev/null 2>&1; then
        run_in "$t"   # exec replaces this process; first one found wins
    fi
done

# No terminal emulator at all: run headless, log the result, and notify if we can.
LOG="$DIR/accutime-last-run.log"
bash "$TARGET" >"$LOG" 2>&1
command -v notify-send >/dev/null 2>&1 && notify-send "AccuTime Sync" "Finished — log: $LOG"
