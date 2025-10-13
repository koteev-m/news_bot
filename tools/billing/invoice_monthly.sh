#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE_URL:?}"
TENANT="${TENANT_ID:?}"
FROM="${FROM:-$(date -u -d '1 month ago' +%Y-%m-01T00:00:00Z)}"
TO="${TO:-$(date -u +%Y-%m-01T00:00:00Z)}"
TAX="${TAX_RATE:-0}"

curl -sS -X POST "$BASE/api/billing/invoice/issue" \
  -H 'Content-Type: application/json' \
  -H "X-Tenant-Slug: ${TENANT}" \
  -d "{\"from\":\"$FROM\",\"to\":\"$TO\",\"taxRate\":$TAX}" | jq .
