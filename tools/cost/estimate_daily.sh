#!/usr/bin/env bash
set -euo pipefail

red(){ printf '\033[31m%s\033[0m\n' "$*"; }
green(){ printf '\033[32m%s\033[0m\n' "$*"; }

PROM_URL="${PROM_URL:?PROM_URL required (e.g., http://localhost:9090)}"
OUT="${OUT:-cost_daily.txt}"

query_total='sum_over_time(service_total_cost_usd_per_hour[24h]) / (3600 / 15)'
# Fallback: integrate hourly cost over 24h using 15s scrape-interval approximation.

resp=$(curl -sS --get "$PROM_URL/api/v1/query" --data-urlencode "query=$query_total")
status=$(jq -r '.status' <<<"$resp")
if [[ "$status" != "success" ]]; then
  red "[FAIL] Prometheus query failed"; exit 1
fi

val=$(jq -r '.data.result[0].value[1]' <<<"$resp")
printf "Date (UTC): %s\nEstimated total cost (last 24h): $%0.4f\n" "$(date -u +"%Y-%m-%d")" "${val:-0}" | tee "$OUT"
green "[OK] report -> $OUT"
