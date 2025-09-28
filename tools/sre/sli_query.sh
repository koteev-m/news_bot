#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: PROM_URL=<prometheus-url> $(basename "$0") <sli_name> <interval>
Supported SLI names: webhook_p95, api_5xx_rate, stars_duplicate_rate
Example:
  PROM_URL=http://localhost:9090 $(basename "$0") webhook_p95 5m
USAGE
}

if [[ ${PROM_URL:-} == "" ]]; then
  echo "[error] PROM_URL environment variable is required" >&2
  usage >&2
  exit 1
fi

if [[ $# -ne 2 ]]; then
  usage >&2
  exit 1
fi

sli_name="$1"
interval="$2"

case "$sli_name" in
  webhook_p95)
    query="histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"app\", uri=\"/telegram/webhook\"}[$interval])))"
    ;;
  api_5xx_rate)
    query="sum(rate(http_server_requests_seconds_count{job=\"app\", status=~\"5..\"}[$interval])) / sum(rate(http_server_requests_seconds_count{job=\"app\"}[$interval]))"
    ;;
  stars_duplicate_rate)
    query="increase(webhook_stars_duplicate_total{job=\"app\"}[$interval]) / clamp_min(increase(webhook_stars_success_total{job=\"app\"}[$interval]), 1)"
    ;;
  *)
    echo "[error] unknown SLI '$sli_name'" >&2
    usage >&2
    exit 1
    ;;
esac

response=$(curl -sS -G "$PROM_URL/api/v1/query" --data-urlencode "query=$query")
status=$(echo "$response" | jq -r '.status' 2>/dev/null || echo "")

if [[ "$status" != "success" ]]; then
  echo "[error] Prometheus query failed" >&2
  echo "$response" >&2
  exit 1
fi

value=$(echo "$response" | jq -r '.data.result[0].value[1]' 2>/dev/null || echo "null")

echo "$sli_name ($interval) = $value"
