#!/usr/bin/env bash
set -euo pipefail
PID="${1:?Usage: record_jfr.sh <pid> [seconds]}"
DUR="${2:-60}"
OUT="${OUT:-recording-$(date +%Y%m%d%H%M%S)}.jfr"
jcmd "$PID" JFR.start name=perf settings=profile duration="${DUR}s" filename="$OUT"
echo "[OK] JFR -> $OUT"
