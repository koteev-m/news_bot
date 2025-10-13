#!/usr/bin/env bash
set -euo pipefail

DAY_INPUT=${1:-}
SEQ_INPUT=${2:-}
if [[ -n "$DAY_INPUT" ]]; then
  DAY=$(date -u -d "$DAY_INPUT" +%Y-%m-%d)
else
  DAY=$(date -u -d 'yesterday' +%Y-%m-%d)
fi

if [[ -z "${DATABASE_URL:-}" ]]; then
  echo "DATABASE_URL environment variable is required" >&2
  exit 1
fi

CHECK_SQL="SELECT last_seq_id, root_hash, signature FROM audit_checkpoints WHERE day = DATE '$DAY';"
CHECK_ROW=$(psql "$DATABASE_URL" -At -F '|' -c "$CHECK_SQL")
if [[ -z "$CHECK_ROW" ]]; then
  echo "No checkpoint recorded for $DAY" >&2
  exit 1
fi
IFS='|' read -r CHECK_SEQ CHECK_HASH CHECK_SIG <<< "$CHECK_ROW"
if [[ -z "$CHECK_SEQ" || -z "$CHECK_HASH" ]]; then
  echo "Incomplete checkpoint data for $DAY" >&2
  exit 1
fi

TARGET_SEQ=${SEQ_INPUT:-$CHECK_SEQ}
RECORDS_SQL="SELECT COALESCE(json_agg(row_to_json(t) ORDER BY seq_id)::text, '[]') FROM (SELECT seq_id, ts, actor_type, actor_id, tenant_id, action, resource, meta_json, prev_hash, hash FROM audit_ledger WHERE seq_id <= $TARGET_SEQ ORDER BY seq_id) AS t;"
RECORDS_JSON=$(psql "$DATABASE_URL" -At -F '' -c "$RECORDS_SQL")
if [[ -z "$RECORDS_JSON" ]]; then
  RECORDS_JSON='[]'
fi

python3 - <<'PY' "$RECORDS_JSON" "$TARGET_SEQ" "$CHECK_HASH"
import hashlib
import json
import sys
from datetime import datetime, timezone

if len(sys.argv) != 4:
    raise SystemExit("Usage: ledger_verify.sh [day] [seq]")
records_json, target_seq, expected_root = sys.argv[1:]
records = json.loads(records_json)
if not records:
    raise SystemExit("Ledger is empty; nothing to verify")
prev = "GENESIS"
last_hash = None

def canonical(element):
    if isinstance(element, dict):
        return {key: canonical(element[key]) for key in sorted(element)}
    if isinstance(element, list):
        return [canonical(item) for item in element]
    return element

def normalise_ts(value: str) -> datetime:
    candidate = value.replace(' ', 'T').replace('Z', '+00:00')
    tz_index = max(candidate.rfind('+'), candidate.rfind('-'))
    if tz_index > 0:
        head = candidate[:tz_index]
        tz = candidate[tz_index:]
        if ':' not in tz and tz not in {'Z', '+Z', '-Z'}:
            if len(tz) == 3:
                tz = f"{tz}:00"
            elif len(tz) == 5:
                tz = f"{tz[:3]}:{tz[3:]}"
        candidate = head + tz
    return datetime.fromisoformat(candidate).astimezone(timezone.utc)

for record in records:
    seq = record["seq_id"]
    ts_raw = record["ts"]
    ts = normalise_ts(ts_raw)
    ts_string = ts.isoformat().replace('+00:00', 'Z')
    actor_type = str(record["actor_type"]).lower()
    actor_id = record.get("actor_id") or ""
    tenant_id = record.get("tenant_id")
    tenant_str = "" if tenant_id is None else str(tenant_id)
    action = record["action"]
    resource = record["resource"]
    meta = canonical(record.get("meta_json") or {})
    meta_string = json.dumps(meta, separators=(",", ":"))
    prev_hash = record["prev_hash"]
    data = "|".join([
        str(seq),
        ts_string,
        actor_type,
        actor_id,
        tenant_str,
        action,
        resource,
        meta_string,
        prev,
    ])
    digest = hashlib.sha256(data.encode("utf-8")).hexdigest()
    if digest != record["hash"]:
        raise SystemExit(f"Hash mismatch at seq {seq}: expected {digest}, stored {record['hash']}")
    if prev_hash != prev:
        raise SystemExit(f"Broken chain at seq {seq}: prev_hash {prev_hash} vs {prev}")
    prev = record["hash"]
    last_hash = prev

if last_hash != expected_root:
    raise SystemExit(f"Root hash mismatch: computed {last_hash}, checkpoint {expected_root}")

print(f"Ledger verified up to seq {target_seq} with root {last_hash}")
PY

echo "Checkpoint signature: $CHECK_SIG"
