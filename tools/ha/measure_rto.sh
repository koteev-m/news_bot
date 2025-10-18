#!/usr/bin/env bash
set -euo pipefail
HOST="${HOST:?}"            # глобальное имя (Route53 record)
PATH_CHECK="${PATH_CHECK:-/healthz}"
INTERVAL="${INTERVAL:-3}"
TIMEOUT="${TIMEOUT:-600}"   # max seconds
EXPECTED_CNAME="${EXPECTED_CNAME:-}" # опционально: ожидаемый CNAME SECONDARY

echo "[INFO] Measuring RTO at https://${HOST}${PATH_CHECK}"
start_ts=$(date +%s)
deadline=$(( start_ts + TIMEOUT ))

while true; do
  now=$(date +%s)
  [ "$now" -gt "$deadline" ] && { echo "[FAIL] timeout ${TIMEOUT}s"; exit 1; }
  code=$(curl -sS -o /dev/null -w '%{http_code}' "https://${HOST}${PATH_CHECK}" || echo 000)
  cname=$(dig +short CNAME "${HOST}" || true)
  if [ "$code" = "200" ]; then
    if [ -n "$EXPECTED_CNAME" ] && [ "$cname" != "$EXPECTED_CNAME" ]; then
      echo "[INFO] 200 but CNAME=${cname} != expected ${EXPECTED_CNAME}, continue…"
    else
      end_ts=$(date +%s)
      rto=$(( end_ts - start_ts ))
      echo "RTO_SECONDS=${rto}" | tee rto.env
      echo "[OK] RTO ${rto}s (CNAME=${cname})"
      exit 0
    fi
  fi
  sleep "$INTERVAL"
done
