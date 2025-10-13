# P74 — Audit, Risk & Policy Engine (Continuous Governance)

## Policy-as-Code
- Rego политики: `governance/policies/**`
- Проверка Helm-рендера: `tools/governance/policy_check.sh` (Conftest)

## Evidence
- Сбор артефактов: `tools/governance/collect_evidence.sh`
- Источники: CodeQL/Trivy/Gitleaks SARIF, CV-отчёт, FinOps отчёт, OpenAPI spec

## Risk Score
- Скрипт: `tools/governance/risk_score.py`
- Выход: `.governance/governance-report.json` (оценки по Security / Reliability / Compliance + overall)

## CI
- **Governance Gate** — политики + сбор доказательств + риск-оценка
- **Governance Daily** — ежедневный отчёт

> Храните отчёты в артефактах и ссылках в релизах; не добавляйте секреты в репозиторий.
