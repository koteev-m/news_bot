#!/usr/bin/env bash
set -euo pipefail

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }

for cmd in curl timeout; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    red "[FAIL] required command '$cmd' not found"
    exit 127
  fi
done

BASE="${BASE_URL:?BASE_URL required}"
WEBHOOK_SECRET="${WEBHOOK_SECRET:?WEBHOOK_SECRET required}"
TG_USER_ID="${TG_USER_ID:?TG_USER_ID required}"
REPORT_JSON="${REPORT_JSON:-synthetics_report.json}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-20}"
CURL_OPTS=(--silent --show-error --write-out '\n%{http_code}\n' --max-time 10)

pass=0
fail=0
results=()
healthz_ok=0
health_db_ok=0
webhook_ok=0

now_iso() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

record_result() {
  local name="$1" code="$2" ok="$3" rc="$4" start_ts="$5" end_ts="$6"
  results+=("{\"name\":\"$name\",\"code\":$code,\"ok\":$ok,\"rc\":$rc,\"start\":\"$start_ts\",\"end\":\"$end_ts\"}")
}

run_curl() {
  local method="$1" url="$2" payload="$3"
  if [[ -n "$payload" ]]; then
    timeout "$REQUEST_TIMEOUT" curl "${CURL_OPTS[@]}" -H "content-type: application/json" -H "X-Telegram-Bot-Api-Secret-Token: ${WEBHOOK_SECRET}" -X "$method" "$url" -d "$payload"
  else
    timeout "$REQUEST_TIMEOUT" curl "${CURL_OPTS[@]}" -X "$method" "$url"
  fi
}

check_get () {
  local name="$1" url="$2"
  local start_ts end_ts rc resp code ok=0
  start_ts=$(now_iso)
  set +e
  resp=$(run_curl GET "$url" "")
  rc=$?
  set -e
  end_ts=$(now_iso)
  code=$(printf "%s" "$resp" | tail -n1)
  if [[ ! "$code" =~ ^[0-9]{3}$ ]]; then
    code=0
  fi
  if [[ $rc -eq 0 && $code -eq 200 ]]; then
    ok=1
  fi
  if [[ "$name" == "quotes" && $rc -eq 0 && ( $code -eq 200 || $code -eq 404 ) ]]; then
    ok=1
  fi
  if [[ $ok -eq 1 ]]; then
    pass=$((pass+1))
    green "[OK] $name $code"
  else
    fail=$((fail+1))
    if [[ $rc -eq 124 ]]; then
      red "[FAIL] $name timeout after ${REQUEST_TIMEOUT}s"
    else
      red "[FAIL] $name $code rc=$rc"
    fi
  fi
  if [[ "$name" == "healthz" ]]; then healthz_ok=$ok; fi
  if [[ "$name" == "health_db" ]]; then health_db_ok=$ok; fi
  record_result "$name" "$code" "$ok" "$rc" "$start_ts" "$end_ts"
}

check_webhook () {
  local name="webhook" url="$BASE/telegram/webhook"
  local payload
  payload=$(printf '{"message":{"from":{"id":%s},"successful_payment":{"currency":"XTR","total_amount":1234,"invoice_payload":"%s:PRO:synthetic","provider_payment_charge_id":"pmt_synth_%s"}}}' "$TG_USER_ID" "$TG_USER_ID" "$(date +%s)")
  local start_ts end_ts rc resp code ok=0
  start_ts=$(now_iso)
  set +e
  resp=$(run_curl POST "$url" "$payload")
  rc=$?
  set -e
  end_ts=$(now_iso)
  code=$(printf "%s" "$resp" | tail -n1)
  if [[ ! "$code" =~ ^[0-9]{3}$ ]]; then
    code=0
  fi
  if [[ $rc -eq 0 && $code -eq 200 ]]; then
    ok=1
  fi
  if [[ $ok -eq 1 ]]; then
    pass=$((pass+1))
    green "[OK] $name $code"
  else
    fail=$((fail+1))
    if [[ $rc -eq 124 ]]; then
      red "[FAIL] $name timeout after ${REQUEST_TIMEOUT}s"
    else
      red "[FAIL] $name $code rc=$rc"
    fi
  fi
  webhook_ok=$ok
  record_result "$name" "$code" "$ok" "$rc" "$start_ts" "$end_ts"
}

yellow "Running synthetics against $BASE"

check_get "healthz"     "$BASE/healthz"
check_get "health_db"   "$BASE/health/db"
check_get "quotes"      "$BASE/api/quotes/closeOrLast?instrumentId=1&date=2025-09-20"
check_webhook

results_json="[]"
if ((${#results[@]})); then
  printf -v joined "%s," "${results[@]}"
  results_json="[${joined%,}]"
fi

cat <<JSON > "$REPORT_JSON"
{
  "timestamp": "$(now_iso)",
  "base": "${BASE}",
  "pass": $pass,
  "fail": $fail,
  "results": $results_json
}
JSON

mandatory_sum=$((healthz_ok + health_db_ok + webhook_ok))
if [[ $mandatory_sum -ne 3 || $fail -gt 1 ]]; then
  red "[FAIL] synthetics: pass=$pass fail=$fail mandatory_ok=$mandatory_sum"
  exit 1
fi

green "[OK] synthetics: pass=$pass fail=$fail"
exit 0
