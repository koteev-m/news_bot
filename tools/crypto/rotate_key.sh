#!/usr/bin/env bash
set -euo pipefail
# 1) Create new key version in KMS manually.
# 2) Configure VAULT_KEY_ID_NEW in CI or deployment environment.
# 3) Re-encrypt existing envelopes with new key.

OLD="${VAULT_KEY_ID_OLD:?}"
NEW="${VAULT_KEY_ID_NEW:?}"
echo "[INFO] rotate keys $OLD -> $NEW (re-encrypt stored envelopes)"
echo "[HINT] implement migration job that iterates secrets and rewraps DEKs"
