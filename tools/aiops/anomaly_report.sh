#!/usr/bin/env bash
set -euo pipefail
PROM_URL="${PROM_URL:?}"
QUERY="${QUERY:-sum(rate(http_server_requests_seconds_count[5m]))}"
export PROM_URL QUERY ZSCORE="${ZSCORE:-3.0}" WINDOW="${WINDOW:-49}" STEP="${STEP:-60s}"
OUT_JSON="aiops_anomalies.json"
OUT_MD="aiops_anomalies.md"

python3 tools/aiops/anomaly_detect.py > "$OUT_JSON"
ANOM=$(jq '.anomalies|length' "$OUT_JSON")
TS=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
{
  echo "# AIOps anomalies ($TS)"
  echo ""
  echo "- Query: \`$QUERY\`"
  echo "- Points: $(jq '.points' "$OUT_JSON")"
  echo "- Anomalies: $ANOM"
  echo ""
  echo "| ts (UTC) | value | baseline | z |"
  echo "|---|---:|---:|---:|"
  jq -r '.anomalies[] | "| " + ( .ts|todateiso8601 ) + " | " + ( .val|tostring ) + " | " + ( .base|tostring ) + " | " + ( .z|tostring ) + " |"' "$OUT_JSON" | head -n 50
} > "$OUT_MD"
echo "[OK] anomalies: $ANOM"
