#!/usr/bin/env bash
set -euo pipefail
PROM_URL="${PROM_URL:?}"
CFG="${CFG:?cv/thresholds.yml}"

p95_max=$(yq -r '.slo.p95_seconds_max' "$CFG")
err_max=$(yq -r '.slo.error_rate_max' "$CFG")
burn_max=$(yq -r '.slo.burn_rate_5m_max' "$CFG")

p95=$(curl -sS --get "${PROM_URL}/api/v1/query" --data-urlencode 'query=histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))' | jq -r '.data.result[0].value[1]' || echo 0)
err=$(curl -sS --get "${PROM_URL}/api/v1/query" --data-urlencode 'query=(sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))/clamp_min(sum(rate(http_server_requests_seconds_count[5m])),1))' | jq -r '.data.result[0].value[1]' || echo 0)
burn=$(curl -sS --get "${PROM_URL}/api/v1/query" --data-urlencode 'query=slo:newsbot-api:api-availability:error_budget_burn_rate:5m' | jq -r '.data.result[0].value[1]' || echo 0)

awk -v v="$p95" -v m="$p95_max" 'BEGIN{ if(v+0>m+0){ printf("P95 %f > %f\n",v,m); exit 1} }'
awk -v v="$err" -v m="$err_max" 'BEGIN{ if(v+0>m+0){ printf("ERR %f > %f\n",v,m); exit 1} }'
awk -v v="$burn" -v m="$burn_max" 'BEGIN{ if(v+0>m+0){ printf("BURN %f > %f\n",v,m); exit 1} }'
echo "[OK] SLO gates: p95=${p95}s, err=${err}, burn=${burn}"
