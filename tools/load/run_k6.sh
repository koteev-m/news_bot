#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${REPO_ROOT}/deploy/load/.env"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "${ENV_FILE}"
  set +a
fi

if [[ "${APP_PROFILE:-}" == "prod" ]]; then
  echo "[load] Refusing to run load tests with APP_PROFILE=prod" >&2
  exit 1
fi

if [[ $# -ne 1 ]]; then
  cat <<USAGE >&2
Usage: ${0##*/} <profile>
  portfolio:smoke
  portfolio:ramp
  webhook:smoke
  webhook:burst
USAGE
  exit 64
fi

profile="$1"
script=""
scenario=""

case "${profile}" in
  portfolio:smoke)
    script="${REPO_ROOT}/deploy/load/k6/portfolio_scenario.js"
    scenario="smoke"
    ;;
  portfolio:ramp)
    script="${REPO_ROOT}/deploy/load/k6/portfolio_scenario.js"
    scenario="ramp"
    ;;
  webhook:smoke)
    script="${REPO_ROOT}/deploy/load/k6/webhook_scenario.js"
    scenario="smoke"
    ;;
  webhook:burst)
    script="${REPO_ROOT}/deploy/load/k6/webhook_scenario.js"
    scenario="burst"
    ;;
  *)
    echo "Unknown profile: ${profile}" >&2
    exit 65
    ;;
esac

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 binary is required on PATH" >&2
  exit 127
fi

export K6_SCENARIO="${scenario}"

echo "[load] Running profile ${profile}" >&2
printf '[load] Command: %s\n' "k6 run ${script}" >&2

exec k6 run "${script}"
