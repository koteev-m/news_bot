#!/usr/bin/env bash
set -euo pipefail
PID="${1:?Usage: profile_async.sh <pid> [seconds]}"
DUR="${2:-60}"
OUT="${OUT:-profile-${PID}-$(date +%Y%m%d%H%M%S)}"
# download async-profiler
if [ ! -d "tools/perf/ap" ]; then
  mkdir -p tools/perf
  curl -sSfL https://github.com/async-profiler/async-profiler/releases/download/v3.0/async-profiler-3.0-linux-x64.tar.gz | tar -xz -C tools/perf
  mv tools/perf/async-profiler-3.0-* tools/perf/ap
fi
tools/perf/ap/profiler.sh -e cpu -d "$DUR" -f "${OUT}.svg" "$PID"
echo "[OK] flamegraph -> ${OUT}.svg"
