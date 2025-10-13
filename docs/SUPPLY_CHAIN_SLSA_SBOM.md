# P76 — SBOM & Provenance (SLSA-3)

## Состав
- **SBOM**: Syft → `sbom-image.spdx.json`, `sbom-source.spdx.json`
- **Vuln scan**: Grype → SARIF в Code Scanning
- **Provenance**: SLSA Generator + Cosign attest (keyless OIDC)
- **Verify**: Kyverno `verify-images-provenance` (подписи и тип предиката `slsaprovenance`)

## Запуск
- SBOM локально: `bash tools/sbom/generate_sbom.sh IMAGE_REF=ghcr.io/ORG/REPO:tag`
- CI:
  - `sbom.yml` — генерация и артефакты
  - `grype-scan.yml` — SCA уязвимости (SARIF)
  - `slsa-provenance.yml` — аттестации (Cosign)

## Политика
- Кластер принимает только образы с верифицируемой аттестацией SLSA и подписью cosign.
- SBOM diff в PR: `sbom-diff.yml`

> Реальные секреты не требуются для keyless — используется OIDC GitHub. Для частных реестров — добавьте auth.
