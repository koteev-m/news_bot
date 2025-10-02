#!/usr/bin/env bash
set -euo pipefail

RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
BLUE="\033[34m"
RESET="\033[0m"

skip_miniapp=false
skip_k6=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-miniapp)
      skip_miniapp=true
      shift
      ;;
    --skip-k6)
      skip_k6=true
      shift
      ;;
    *)
      echo -e "${RED}[ERR] Unknown option: $1${RESET}" >&2
      exit 1
      ;;
  esac
done

if [[ "${APP_PROFILE:-}" == "prod" ]]; then
  echo -e "${RED}[ERR] APP_PROFILE=prod detected. Abort preflight on production profile.${RESET}" >&2
  exit 2
fi

log_step() {
  echo -e "${BLUE}==> $1${RESET}"
}

log_warn() {
  echo -e "${YELLOW}[WARN] $1${RESET}"
}

log_ok() {
  echo -e "${GREEN}[OK] $1${RESET}"
}

log_step "Running Gradle lint, tests, and build"
./gradlew ktlintCheck detekt test build

log_step "Executing compliance audit checks"
tools/audit/run_all.sh --skip-build

if [[ -f miniapp/package.json ]]; then
  if [[ "$skip_miniapp" == "true" ]]; then
    log_warn "Skipping miniapp E2E checks by flag"
  else
    if ! command -v pnpm >/dev/null 2>&1; then
      echo -e "${RED}[ERR] pnpm is required for miniapp checks. Install pnpm or rerun with --skip-miniapp.${RESET}" >&2
      exit 1
    fi
    log_step "Running miniapp E2E smoke"
    pushd miniapp > /dev/null
    pnpm install --frozen-lockfile
    pnpm build
    pnpm test:e2e -- --reporter=list
    popd > /dev/null
  fi
else
  log_warn "Miniapp project not detected; skipping E2E smoke"
fi

if [[ "$skip_k6" == "true" ]]; then
  log_warn "Skipping k6 dry-run by flag"
else
  if compgen -G "deploy/load/k6/*_scenario.js" > /dev/null; then
    if ! command -v k6 >/dev/null 2>&1; then
      echo -e "${RED}[ERR] k6 binary is required. Install k6 or rerun with --skip-k6.${RESET}" >&2
      exit 1
    fi
    log_step "Running k6 dry-run for smoke scenarios"
    for script in deploy/load/k6/*_scenario.js; do
      echo -e "${BLUE}--- $(basename "$script")${RESET}"
      BASE_URL=https://example.invalid k6 run --vus 1 --duration 1s "$script"
    done
  else
    log_warn "No k6 scenario scripts detected"
  fi
fi

log_ok "Preflight checks completed"
