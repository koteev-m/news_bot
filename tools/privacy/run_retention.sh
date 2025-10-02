#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
JWT="${JWT:?JWT required}"
curl -s -X POST "$BASE/api/admin/privacy/retention/run" \
  -H "Authorization: Bearer $JWT" | jq .
