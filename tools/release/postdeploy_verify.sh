#!/usr/bin/env bash
set -euo pipefail
trap 'echo -e "\033[31m[FAIL] post-deploy verification failed\033[0m" >&2' ERR

RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
BLUE="\033[34m"
RESET="\033[0m"

require_env() {
  local var="$1"
  if [[ -z "${!var:-}" ]]; then
    echo -e "${RED}[ERR] Environment variable ${var} is required.${RESET}" >&2
    exit 1
  fi
}

require_env BASE_URL
require_env PROM_URL
require_env WEBHOOK_SECRET
require_env TG_USER_ID
require_env APP_PROFILE

log_step() {
  echo -e "${BLUE}==> $1${RESET}"
}

log_ok() {
  echo -e "${GREEN}[OK] $1${RESET}"
}

log_warn() {
  echo -e "${YELLOW}[WARN] $1${RESET}"
}

base_url="${BASE_URL%/}"

http_check() {
  local path="$1"
  local label="$2"
  local tmp
  local status
  tmp=$(mktemp)
  status=$(curl -sS -o "$tmp" -w '%{http_code}' "${base_url}${path}")
  if [[ "$status" != "200" ]]; then
    echo -e "${RED}[ERR] ${label} returned status ${status}${RESET}" >&2
    cat "$tmp" >&2
    rm -f "$tmp"
    exit 1
  fi
  rm -f "$tmp"
  log_ok "${label} healthy"
}

log_step "Checking service health endpoints"
http_check "/healthz" "Health probe"
http_check "/health/db" "Database health"

log_step "Validating /metrics endpoint"
metrics_tmp=$(mktemp)
metrics_status=$(curl -sS -o "$metrics_tmp" -w '%{http_code}' "${base_url}/metrics")
if [[ "$metrics_status" != "200" ]]; then
  echo -e "${RED}[ERR] Metrics endpoint returned status ${metrics_status}${RESET}" >&2
  cat "$metrics_tmp" >&2
  rm -f "$metrics_tmp"
  exit 1
fi
if ! grep -E '^http_server_requests_seconds_count' "$metrics_tmp" >/dev/null 2>&1; then
  echo -e "${RED}[ERR] Expected http_server_requests_seconds_count metric not found.${RESET}" >&2
  rm -f "$metrics_tmp"
  exit 1
fi
if ! grep -E '^jvm_threads_live_threads' "$metrics_tmp" >/dev/null 2>&1; then
  echo -e "${RED}[ERR] Expected jvm_threads_live_threads metric not found.${RESET}" >&2
  rm -f "$metrics_tmp"
  exit 1
fi
rm -f "$metrics_tmp"
log_ok "Metrics endpoint exposes core telemetry"

prom_query() {
  local description="$1"
  local query="$2"
  local threshold="$3"
  local comparator="$4"
  local response
  local value
  local status
  response=$(curl -sS -G --data-urlencode "query=${query}" "${PROM_URL%/}/api/v1/query")
  if [[ -z "$response" ]]; then
    echo -e "${RED}[ERR] Empty response from Prometheus for ${description}.${RESET}" >&2
    exit 1
  fi
  value=$(PROM_RESPONSE="$response" python - "$threshold" "$comparator" <<'PY'
import json
import os
import sys

data = json.loads(os.environ["PROM_RESPONSE"])
threshold = float(sys.argv[1])
comparator = sys.argv[2]

if data.get("status") != "success":
    print("ERROR", file=sys.stderr)
    sys.exit(1)
result = data.get("data", {}).get("result", [])
if not result:
    value = 0.0
else:
    try:
        value = float(result[0]["value"][1])
    except (KeyError, ValueError, IndexError):
        print("ERROR", file=sys.stderr)
        sys.exit(1)
if comparator == "lt":
    ok = value < threshold
elif comparator == "le":
    ok = value <= threshold
elif comparator == "gt":
    ok = value > threshold
elif comparator == "ge":
    ok = value >= threshold
else:
    print("ERROR", file=sys.stderr)
    sys.exit(1)
print(f"{value}")
if not ok:
    sys.exit(2)
PY
)
  status=$?
  if [[ $status -eq 0 ]]; then
    log_ok "${description}: ${value} (threshold ${comparator} ${threshold})"
  elif [[ $status -eq 2 ]]; then
    echo -e "${RED}[ERR] ${description} out of bounds: ${value}${RESET}" >&2
    exit 1
  else
    echo -e "${RED}[ERR] Failed to evaluate PromQL for ${description}.${RESET}" >&2
    exit 1
  fi
}

log_step "Evaluating Prometheus SLO queries"
prom_query "HTTP 5xx rate (5m)" "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))" 0.02 lt
prom_query "XTR webhook p95 latency (5m)" "histogram_quantile(0.95, sum(rate(webhook_duration_seconds_bucket{integration=\"xtr\"}[5m])) by (le))" 1.5 lt

if [[ -n "${JWT:-}" ]]; then
  log_step "Authenticated API smoke for portfolio"
  tmp=$(mktemp)
  status=$(curl -sS -H "Authorization: Bearer ${JWT}" -o "$tmp" -w '%{http_code}' "${base_url}/api/portfolio")
  if [[ "$status" != "200" ]]; then
    echo -e "${RED}[ERR] Authenticated portfolio API returned status ${status}${RESET}" >&2
    cat "$tmp" >&2
    rm -f "$tmp"
    exit 1
  fi
  rm -f "$tmp"
  log_ok "Portfolio API responded successfully"
else
  log_warn "JWT not provided; skipping authenticated API smoke"
fi

log_step "Simulating XTR webhook delivery"
payload_id="xtr-$(date +%s)"
for attempt in 1 2; do
  status=$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST "${base_url}/api/webhooks/xtr" \
    -H "Content-Type: application/json" \
    -H "X-Webhook-Secret: ${WEBHOOK_SECRET}" \
    -d "{\"event\":\"payment\",\"id\":\"${payload_id}\",\"amount\":0}")
  if [[ "$status" != "200" ]]; then
    echo -e "${RED}[ERR] Webhook attempt ${attempt} returned status ${status}${RESET}" >&2
    exit 1
  fi
  log_ok "Webhook attempt ${attempt} returned 200"
done

log_step "Verifying TG_USER_ID configured"
log_ok "Escalation contact configured"

trap - ERR
log_ok "post-deploy verified"
