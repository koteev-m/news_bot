#!/usr/bin/env bash
set -euo pipefail

# ENV (секреты/параметры в CI):
# PROM_URL          – Prometheus HTTP API (Grafana Cloud Prometheus Query URL)
# LOKI_URL          – Loki push endpoint (e.g., https://logs-prod.grafana.net/loki/api/v1/push)
# LOKI_USER         – Grafana Cloud Loki user
# LOKI_API_KEY      – API key for Loki
# GRAFANA_API_URL   – Grafana API base (e.g., https://grafana.example/api)
# GRAFANA_API_TOKEN – Grafana API token (annotations)
# WATT_PER_VCPU     – override watts per vCPU (optional)
# CARBON_GCO2_PER_KWH – override regional carbon intensity (gCO2/kWh) (optional)

jqfail(){ echo "[FAIL] $1"; exit 1; }

END=$(date -u +%s)
START=$((END - 24*3600))
PROM="${PROM_URL:?PROM_URL required}"

q() {
  curl -sS --get "${PROM}/api/v1/query" --data-urlencode "query=$1" | jq -r '.data.result[0].value[1]'
}

# overrides (optional)
if [ -n "${WATT_PER_VCPU:-}" ]; then
  WATT=${WATT_PER_VCPU}
else
  WATT=$(q 'finops:watt_per_vcpu') || WATT=15
fi
if [ -n "${CARBON_GCO2_PER_KWH:-}" ]; then
  CI_GCO2=${CARBON_GCO2_PER_KWH}
else
  CI_GCO2=$(q 'finops:carbon_gco2_per_kwh') || CI_GCO2=400
fi

# cost/energy/carbon (integral за 24h)
# Приближение: средний почасовой × 24 (или integrate через sum_over_time если есть range rules)
COST_PH=$(q 'total_cost_usd_per_hour') || COST_PH=0
ENERGY_PH=$(q 'total_energy_kwh_per_hour') || ENERGY_PH=0
CARBON_PH=$(q 'total_carbon_gco2_per_hour') || CARBON_PH=0

COST_24H=$(awk -v x="$COST_PH" 'BEGIN{printf "%.4f", x*24}')
ENERGY_24H=$(awk -v x="$ENERGY_PH" 'BEGIN{printf "%.4f", x*24}')
CARBON_24H=$(awk -v x="$CARBON_PH" 'BEGIN{printf "%.2f", x*24}')

TS_ISO=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
REPORT=$(cat <<JSON
{
  "timestamp":"${TS_ISO}",
  "cost_usd_per_hour": ${COST_PH},
  "energy_kwh_per_hour": ${ENERGY_PH},
  "carbon_gco2_per_hour": ${CARBON_PH},
  "cost_usd_24h": ${COST_24H},
  "energy_kwh_24h": ${ENERGY_24H},
  "carbon_gco2_24h": ${CARBON_24H},
  "params": { "watt_per_vcpu": ${WATT}, "carbon_gco2_per_kwh": ${CI_GCO2} }
}
JSON
)

echo "$REPORT" | jq . >/dev/null || jqfail "bad JSON"

# Push to Loki as a log line (stream: finops)
if [ -n "${LOKI_URL:-}" ] && [ -n "${LOKI_USER:-}" ] && [ -n "${LOKI_API_KEY:-}" ]; then
  LOG_JSON=$(jq -n --arg t "$(date +%s%N)" --arg msg "$REPORT" \
    '{streams:[{stream:{job:"finops",app:"newsbot"},values:[[ $t, $msg ]]}]}')
  curl -sS -u "${LOKI_USER}:${LOKI_API_KEY}" -H "Content-Type: application/json" \
    -X POST "${LOKI_URL}" -d "${LOG_JSON}" >/dev/null && echo "[OK] pushed to Loki"
fi

# Create Grafana annotation (optional)
if [ -n "${GRAFANA_API_URL:-}" ] && [ -n "${GRAFANA_API_TOKEN:-}" ]; then
  ANN=$(jq -n --arg text "FinOps 24h: \$${COST_24H}, ${ENERGY_24H} kWh, ${CARBON_24H} gCO2" \
    --arg when "$(($(date +%s)*1000))" \
    '{dashboardId: null, tags:["finops","daily"], text:$text, time:$when}')
  curl -sS -H "Authorization: Bearer ${GRAFANA_API_TOKEN}" -H "Content-Type: application/json" \
    -X POST "${GRAFANA_API_URL}/annotations" -d "${ANN}" >/dev/null && echo "[OK] grafana annotation"
fi

# Plain text report output
printf "Date (UTC): %s\nCost: $%s (24h), Energy: %s kWh (24h), Carbon: %s gCO2 (24h)\n" \
  "$TS_ISO" "$COST_24H" "$ENERGY_24H" "$CARBON_24H" | tee finops_daily.txt
