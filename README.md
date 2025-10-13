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
