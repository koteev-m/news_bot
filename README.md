## P76 — SBOM & Provenance (SLSA-3)

- SBOM: `tools/sbom/generate_sbom.sh`, CI `sbom.yml`
- Vulns: `grype-scan.yml` (SARIF → Code Scanning)
- Provenance: `slsa-provenance.yml` (SLSA predicate + Cosign attest)
- Verify in cluster: `k8s/kyverno/policies/verify-images-provenance.yaml`
- SBOM diff gate: `.github/workflows/sbom-diff.yml`

Быстрый старт:
```bash
bash tools/sbom/generate_sbom.sh IMAGE_REF=ghcr.io/ORG/REPO:latest



```

⸻

## P77 — Multi-tenant & Enterprise RBAC

- Миграции: `storage/src/main/resources/db/migration/V11__multitenancy_rbac.sql`
- Core: `core/src/main/kotlin/tenancy/*`
- Storage: `storage/src/main/kotlin/repo/TenancyRepository.kt`
- App: `app/src/main/kotlin/tenancy/*`, `AppTenancy.kt`, `routes/AdminTenancyRoutes.kt`
- Rate limit per-tenant: `app/src/main/kotlin/security/TenantRateLimit.kt`

Быстрый старт:
```bash
./gradlew :storage:flywayMigrate
./gradlew :app:test --tests tenancy.*
```
