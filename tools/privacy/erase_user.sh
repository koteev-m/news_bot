#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
JWT="${JWT:?JWT required}"
USER_ID="${1:?Usage: erase_user.sh <user_id> [--dry]}"
DRY="${2:-}"
curl -s -X POST "$BASE/api/admin/privacy/erase" \
  -H "Authorization: Bearer $JWT" -H "content-type: application/json" \
  -d "{\"userId\": $USER_ID, \"dryRun\": $( [[ "$DRY" == "--dry" ]] && echo true || echo false )}" | jq .
