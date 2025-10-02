#!/usr/bin/env bash
set -euo pipefail

RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
BLUE="\033[34m"
RESET="\033[0m"

if [[ -z "${BASE_URL:-}" ]]; then
  echo -e "${RED}[ERR] BASE_URL environment variable is required.${RESET}" >&2
  exit 1
fi

base_url="${BASE_URL%/}"

log_step() {
  echo -e "${BLUE}==> $1${RESET}"
}

log_ok() {
  echo -e "${GREEN}[OK] $1${RESET}"
}

log_warn() {
  echo -e "${YELLOW}[WARN] $1${RESET}"
}

http_check() {
  local path="$1"
  local label="$2"
  local tmp
  tmp=$(mktemp)
  local status
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

log_step "Synthetic probe: health endpoints"
http_check "/healthz" "Healthz"
http_check "/health/db" "Database health"

log_step "Synthetic probe: metrics availability"
metrics_tmp=$(mktemp)
metrics_status=$(curl -sS -o "$metrics_tmp" -w '%{http_code}' "${base_url}/metrics")
if [[ "$metrics_status" != "200" ]]; then
  echo -e "${RED}[ERR] Metrics endpoint returned status ${metrics_status}${RESET}" >&2
  cat "$metrics_tmp" >&2
  rm -f "$metrics_tmp"
  exit 1
fi
if ! grep -E '^# HELP' "$metrics_tmp" >/dev/null 2>&1; then
  echo -e "${RED}[ERR] Metrics payload missing HELP headers.${RESET}" >&2
  rm -f "$metrics_tmp"
  exit 1
fi
rm -f "$metrics_tmp"
log_ok "Metrics endpoint reachable"

if [[ -n "${JWT:-}" ]]; then
  log_step "Synthetic probe: authenticated portfolio API"
  tmp=$(mktemp)
  status=$(curl -sS -H "Authorization: Bearer ${JWT}" -o "$tmp" -w '%{http_code}' "${base_url}/api/portfolio")
  if [[ "$status" != "200" ]]; then
    echo -e "${RED}[ERR] Portfolio API returned status ${status}${RESET}" >&2
    cat "$tmp" >&2
    rm -f "$tmp"
    exit 1
  fi
  rm -f "$tmp"
  log_ok "Portfolio API responded"
else
  log_warn "JWT not provided; skipping authenticated API probe"
fi

log_ok "Synthetic probe completed"
