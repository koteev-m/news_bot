# Secrets Hygiene (Gitleaks)

- Config: `.gitleaks.toml` (allowlist for docs/samples/build artifacts).
- CI: `.github/workflows/gitleaks.yml` — SARIF → Code Scanning Alerts.
- Local:
  ```bash
  bash tools/security/gitleaks_local.sh
  ```
- Hooks:
  - `tools/git-hooks/pre-commit` — runs `gitleaks detect --staged --redact`, blocks commit on findings.

False positives:
- Add to `.gitleaks.toml` allowlist with clear justification and review date.
