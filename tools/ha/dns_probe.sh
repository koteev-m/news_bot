#!/usr/bin/env bash
set -euo pipefail
HOST="${HOST:?}"
INTERVAL="${INTERVAL:-5}"
DUR="${DUR:-300}" # seconds
END=$(( $(date +%s) + DUR ))
echo "ts,answer,ttl" | tee dns_probe.csv
while [ "$(date +%s)" -lt "$END" ]; do
  ans=$(dig +short CNAME "${HOST}" || true)
  ttl=$(dig +nocmd "${HOST}" CNAME +noall +answer 2>/dev/null | awk '{print $2}' | head -n1)
  ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  echo "${ts},${ans:-<empty>},${ttl:-NA}" | tee -a dns_probe.csv
  sleep "$INTERVAL"
done
echo "[OK] dns_probe.csv ready"
