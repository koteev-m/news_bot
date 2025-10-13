#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:?}"
CFG="${CFG:?cv/thresholds.yml}"
to=$(yq -r '.availability.healthz_timeout_s' "$CFG")

for ep in $(yq -r '.availability.endpoints[].path' "$CFG"); do
  code=$(curl -sS -m "$to" -o /dev/null -w '%{http_code}' "${BASE_URL}${ep}" || echo 000)
  [ "$code" = "200" ] || { echo "[FAIL] ${ep} -> ${code}"; exit 1; }
  echo "[OK] ${ep} -> 200"
done
