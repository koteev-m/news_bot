#!/usr/bin/env bash
set -euo pipefail
PROM_URL="${PROM_URL:?}"
LOKI_URL="${LOKI_URL:?}"
TEMPO_URL="${TEMPO_URL:?}"
BASE_URL="${BASE_URL:?}"
CFG="${CFG:?cv/thresholds.yml}"

need_metrics=$(yq -r '.observability.metrics_endpoint_required' "$CFG")
if [ "$need_metrics" = "true" ]; then
  curl -sSf "${BASE_URL}/metrics" >/dev/null && echo "[OK] /metrics reachable"
fi

# Loki error-rate
q=$(yq -r '.observability.logs_query.query' "$CFG")
max_qps=$(yq -r '.observability.loki_error_rate_qps_max' "$CFG")
# Простейшая оценка: кол-во строк за 300s / 300
END=$(date +%s)
START=$((END-300))
resp=$(curl -sS --get "${LOKI_URL}/loki/api/v1/query_range" --data-urlencode "query=${q}" --data-urlencode "start=${START}000000000" --data-urlencode "end=${END}000000000" --data-urlencode "step=30")
count=$(echo "$resp" | jq -r '[.data.result[].values | length] | add' 2>/dev/null || echo 0)
qps=$(awk -v c="${count:-0}" 'BEGIN{ printf "%.3f", (c/300.0) }')
awk -v v="$qps" -v m="$max_qps" 'BEGIN{ if(v+0>m+0){ printf("Loki error rate %f > %f\n",v,m); exit 1} }'
echo "[OK] Loki error-rate ${qps} <= ${max_qps}"

# Tempo trace check
need_trace=$(yq -r '.observability.tempo_trace_check' "$CFG")
if [ "$need_trace" = "true" ]; then
  curl -sS "${TEMPO_URL}/api/search/tags" | jq -e . >/dev/null && echo "[OK] Tempo reachable"
fi
