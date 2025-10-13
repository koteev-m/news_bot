## P74 — Continuous Governance (Audit, Risk & Policy Engine)

- Policies: `governance/policies/**` (Rego) + `conftest`
- Evidence: `tools/governance/collect_evidence.sh`
- Risk score: `tools/governance/risk_score.py` → `.governance/governance-report.json`
- CI:
  - `.github/workflows/governance-gate.yml`
  - `.github/workflows/governance-daily.yml`

Локально:
```bash
bash tools/governance/policy_check.sh
bash tools/governance/collect_evidence.sh
python3 tools/governance/risk_score.py
```
