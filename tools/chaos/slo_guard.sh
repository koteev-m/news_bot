#!/usr/bin/env bash
set -euo pipefail

PROM_URL="${PROM_URL:?PROM_URL required}"
SLO_QUERY="${SLO_QUERY:-slo:newsbot-api:api-availability:error_budget_burn_rate:5m}"
THRESHOLD="${THRESHOLD:-2}"
WINDOW="${WINDOW:-5m}"

echo "[INFO] Checking SLO burn-rate: $SLO_QUERY < $THRESHOLD"
resp=$(curl -sS --get "$PROM_URL/api/v1/query" --data-urlencode "query=${SLO_QUERY}")
val=$(jq -r '.data.result[0].value[1]' <<<"$resp")
echo "[INFO] burn-rate=$val"
awk -v v="$val" -v t="$THRESHOLD" 'BEGIN { if (v+0 >= t+0) { exit 1 } }' || {
  echo "[FAIL] burn-rate too high ($val >= $THRESHOLD)"; exit 1; }
echo "[OK] SLO guard passed"
