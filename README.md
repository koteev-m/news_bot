## P83 — Vault/KMS Envelope Encryption + Rotation

- Core: `crypto/EnvelopeCrypto.kt`
- KMS Adapter: `integrations/kms/VaultKmsAdapter.kt`
- Storage: `repo/SecretStore.kt`
- API: `crypto/CryptoRoutes.kt`
- Rotation helper: `tools/crypto/rotate_key.sh`
- CI: `.github/workflows/secrets-drift.yml`

ENV (пример):
```env
VAULT_TRANSIT_BASE=https://vault.example/v1/transit
VAULT_TOKEN=***
VAULT_KEY_ID=newsbot-app-key
```

## P84 — Immutable Audit Ledger & Daily Checkpoints

- Core models & hashing: `core/src/main/kotlin/audit/AuditModels.kt`
- Service orchestration: `core/src/main/kotlin/audit/AuditService.kt`
- Repository & tables: `storage/src/main/kotlin/repo/AuditLedgerRepository.kt`
- HTTP integration: `app/src/main/kotlin/audit/AuditPlugin.kt`, `app/src/main/kotlin/audit/AuditRoutes.kt`, `app/src/main/kotlin/AppAudit.kt`
- Tooling: `tools/audit/ledger_sign_daily.sh`, `tools/audit/ledger_verify.sh`
- Workflows: `.github/workflows/audit-daily.yml`, `.github/workflows/audit-verify.yml`
- Runbook & design: `docs/AUDIT_LEDGER.md`

### Enabling

1. Apply the migration `storage/src/main/resources/db/migration/V15__audit_ledger.sql`.
2. Bootstrap the app with `installAuditLayer()` in your application module (after security/tenant plugins).
3. Provide `DATABASE_URL` (read/write) and configure Cosign or GPG credentials for the signing workflow.
4. Schedule the daily checkpoint workflow and trigger the verification workflow after incidents or releases.
5. Use `/api/audit/ledger/last` and `/api/audit/checkpoint/{day}` for operational status and attestations.

### Notes

- Metadata stored in the ledger must remain non-PII.
- Cosign keyless signing requires `id-token: write` permissions in GitHub Actions and `COSIGN_EXPERIMENTAL=1`.
- Verification expects read-only DB credentials and will abort on any hash mismatch.

## P86 — AI-assisted Governance

- RCA сбор: `tools/ai_gov/rca_collect.py`
- Саммари: `tools/ai_gov/llm_summarize.py` (LLM опционально)
- Policy digest: `tools/ai_gov/policy_summarizer.py`
- CI: `.github/workflows/ai-governance-weekly.yml`

Пример (LLM off):
```bash
python3 tools/ai_gov/rca_collect.py
python3 tools/ai_gov/llm_summarize.py
```
