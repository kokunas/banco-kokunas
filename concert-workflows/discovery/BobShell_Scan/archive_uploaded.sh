#!/bin/bash
# Moves the SAST reports converted by the last bob_sast_format.sh run into that run's
# history folder (history/<timestamp>/inputs/) and marks it UPLOADED, so they are not
# converted and uploaded again. The workflow calls it only after Concert accepted the upload.
#
# Usage: archive_uploaded.sh [scan_dir]   (default /home/itzuser/scanner; the workflow passes nothing)
set -euo pipefail
SCAN_DIR=${1:-/home/itzuser/scanner}
LAST=$SCAN_DIR/concert/last_run   # written by bob_sast_format.sh on success

[ -s "$LAST" ] || { echo "no $LAST - run bob_sast_format.sh first"; exit 2; }
HIST_DIR=$(cat "$LAST")
[ -s "$HIST_DIR/inputs.txt" ] || { echo "history folder $HIST_DIR has no inputs.txt"; exit 2; }

mkdir -p "$HIST_DIR/inputs"
n=0
while IFS= read -r f; do
  [ -f "$SCAN_DIR/$f" ] || { echo "MISSING $f (already moved?)"; continue; }
  mv "$SCAN_DIR/$f" "$HIST_DIR/inputs/"
  echo "ARCHIVED $f"
  n=$((n + 1))
done < "$HIST_DIR/inputs.txt"
date -u +%Y-%m-%dT%H:%M:%SZ > "$HIST_DIR/UPLOADED"
rm -f "$LAST"
echo "OK $n report(s) -> $HIST_DIR/inputs"
