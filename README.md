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

## Build & Quality Checks

- Run full verification: `./gradlew clean check --no-daemon --console=plain`
- Lint only: `./gradlew ktlintCheck detekt` (baseline at `config/detekt/baseline.xml`)
- Coverage reports: `./gradlew koverXmlReport` or `./gradlew koverHtmlReport`
- Reports live under `build/reports/kover/xml/report.xml` and `build/reports/kover/html/index.html`
- Detekt reports: `build/reports/detekt/detekt.html` and `build/reports/detekt/detekt.xml`
- ktlint reports: `<module>/build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.txt`

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

## P87 — Multi-Region HA & Global Failover

- Terraform global DNS: `terraform/global/*`
- K8s HA: `k8s/priorityclasses/`, `k8s/overlays/region-*`, `helm/newsbot/values-ha.yaml`
- PostgreSQL logical replication: `deploy/db/pg/*`
- Prometheus federation: `deploy/monitoring/prometheus/federation.yml`
- CI: `.github/workflows/global-health.yml`

Быстрый старт:
```bash
# DNS
terraform -chdir=terraform/global init
terraform -chdir=terraform/global apply \
  -var='zone_id=ZXXXXXXXX' \
  -var='hostname=newsbot.example.com' \
  -var='primary_lb=a123.eu-lb.amazonaws.com' \
  -var='secondary_lb=b456.us-lb.amazonaws.com'

# Проверка
gh workflow run "Global Health & Failover Smoke" -f hostname=newsbot.example.com
```

## P90 — Access Reviews & SoD (Attestations, PAM)

- DB: `V16__access_reviews_sod.sql`
- Core: `access/AccessModels.kt`, `access/AccessServices.kt`
- Repo: `repo/AccessRepoImpl.kt`
- API: `access/AccessRoutes.kt`; wiring: `AppAccess.kt`
- CI: `access-reviews-schedule.yml`
- Docs: `docs/ACCESS_REVIEWS_SOD_PAM.md`

Быстрый старт:
```bash
./gradlew :storage:flywayMigrate
./gradlew :app:compileKotlin
```
