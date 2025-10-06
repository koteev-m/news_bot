#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="deploy/compose/docker-compose.yml"
SERVICE="db"
TIMEOUT=60
INTERVAL=2

docker compose -f "${COMPOSE_FILE}" up -d "${SERVICE}"

echo "Waiting for ${SERVICE} health..."
START=$(date +%s)
while true; do
    STATUS=$(docker compose -f "${COMPOSE_FILE}" ps --format '{{.Status}}' "${SERVICE}" 2>/dev/null || true)
    if [[ "${STATUS}" == *"(healthy)"* ]]; then
        echo "[OK] ${SERVICE} healthy"
        break
    fi

    NOW=$(date +%s)
    ELAPSED=$((NOW - START))
    if (( ELAPSED >= TIMEOUT )); then
        echo "[FAIL] timeout waiting for ${SERVICE} health"
        exit 1
    fi

    sleep "${INTERVAL}"
done
