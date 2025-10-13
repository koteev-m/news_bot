#!/usr/bin/env bash
set -euo pipefail

DAY_INPUT=${1:-}
if [[ -n "$DAY_INPUT" ]]; then
  DAY=$(date -u -d "$DAY_INPUT" +%Y-%m-%d)
else
  DAY=$(date -u -d 'yesterday' +%Y-%m-%d)
fi

if [[ -z "${DATABASE_URL:-}" ]]; then
  echo "DATABASE_URL environment variable is required" >&2
  exit 1
fi

SQL="SELECT seq_id, hash FROM audit_ledger WHERE ts::date <= DATE '$DAY' ORDER BY seq_id DESC LIMIT 1;"
ROW=$(psql "$DATABASE_URL" -At -F '|' -c "$SQL")
if [[ -z "$ROW" ]]; then
  echo "No audit ledger entries found before $DAY" >&2
  exit 1
fi

IFS='|' read -r LAST_SEQ LAST_HASH <<< "$ROW"
if [[ -z "$LAST_HASH" ]]; then
  echo "Failed to determine root hash" >&2
  exit 1
fi

PAYLOAD_FILE=$(mktemp)
SIGNATURE_FILE=$(mktemp)
trap 'rm -f "$PAYLOAD_FILE" "$SIGNATURE_FILE"' EXIT

printf '%s' "$LAST_HASH" > "$PAYLOAD_FILE"
SIGNER=${AUDIT_SIGNER:-cosign}
case "$SIGNER" in
  cosign)
    COSIGN_EXPERIMENTAL=${COSIGN_EXPERIMENTAL:-1} cosign sign-blob --yes --output-signature "$SIGNATURE_FILE" "$PAYLOAD_FILE"
    SIGNATURE=$(base64 -w0 "$SIGNATURE_FILE")
    ;;
  gpg)
    gpg --batch --yes --armor --output "$SIGNATURE_FILE" --detach-sign "$PAYLOAD_FILE"
    SIGNATURE=$(tr -d '\n' < "$SIGNATURE_FILE")
    ;;
  *)
    echo "Unsupported AUDIT_SIGNER value: $SIGNER" >&2
    exit 1
    ;;
esac

python3 - "$DAY" "$LAST_SEQ" "$LAST_HASH" "$SIGNATURE" <<'PY' > checkpoint.json
import json
import sys

if len(sys.argv) != 5:
    raise SystemExit("Usage: ledger_sign_daily.sh [day]")
_, day, last_seq, root_hash, signature = sys.argv
payload = {
    "day": day,
    "last_seq_id": int(last_seq),
    "root_hash": root_hash,
    "signature": signature,
}
json.dump(payload, sys.stdout, separators=(",", ":"))
sys.stdout.write("\n")
PY

ESC_HASH=$(printf "%s" "$LAST_HASH" | sed "s/'/''/g")
ESC_SIG=$(printf "%s" "$SIGNATURE" | sed "s/'/''/g")
psql "$DATABASE_URL" <<SQL
INSERT INTO audit_checkpoints(day, last_seq_id, root_hash, signature)
VALUES (DATE '$DAY', $LAST_SEQ, '$ESC_HASH', '$ESC_SIG')
ON CONFLICT (day)
DO UPDATE SET last_seq_id = EXCLUDED.last_seq_id,
              root_hash = EXCLUDED.root_hash,
              signature = EXCLUDED.signature,
              created_at = now();
SQL

echo "Checkpoint for $DAY written to checkpoint.json (seq $LAST_SEQ)"
