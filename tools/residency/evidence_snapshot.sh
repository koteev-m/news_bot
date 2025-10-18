#!/usr/bin/env bash
set -euo pipefail
TENANT_ID="${TENANT_ID:?}"
REGION="${REGION:?}"
OK="${PI_WRITES_OK:-true}"
psql "${DATABASE_URL}" -c "INSERT INTO residency_evidence(tenant_id, region, pi_writes_ok) VALUES (${TENANT_ID}, '${REGION}', ${OK})"
echo "[OK] residency evidence recorded"
