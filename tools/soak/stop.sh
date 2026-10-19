#!/bin/sh
# Stops the soak servers (and their runner) whose lab folder name contains the given text.
# Matches by working directory, so it never hits the shell that runs this script.
[ -n "$1" ] || { echo "usage: stop.sh <batch-or-lab-substring>"; exit 1; }
for p in $(pgrep -x java); do
  case "$(readlink /proc/$p/cwd)" in *"$1"*) echo "stopping server $p"; kill "$p";; esac
done
for p in $(pgrep -x python3); do
  if tr '\0' ' ' < /proc/$p/cmdline | grep -q -- "--batch $1"; then echo "stopping runner $p"; kill "$p"; fi
done
