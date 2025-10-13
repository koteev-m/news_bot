#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:?}"
CFG="${CFG:?cv/thresholds.yml}"
ENABLED=$(yq -r '.k6.enabled' "$CFG")
[ "$ENABLED" = "true" ] || { echo "[SKIP] k6 disabled"; exit 0; }

VUS=$(yq -r '.k6.vus' "$CFG")
DUR=$(yq -r '.k6.duration' "$CFG")

curl -sS https://raw.githubusercontent.com/grafana/k6/master/dist/bin/linux/x64/k6.zip -o k6.zip >/dev/null
unzip -qq k6.zip -d k6bin && chmod +x k6bin/k6

BASE_URL="$BASE_URL" ./k6bin/k6 run deploy/load/k6/cv_smoke.js --vus "$VUS" --duration "$DUR"
