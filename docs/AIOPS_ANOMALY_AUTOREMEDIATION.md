# P70 — AI-assisted Ops: Anomaly Detection + Auto-Remediation + ChatOps

## Состав
- `anomaly_detect.py` — z-score + медианный baseline, вход через PromQL.
- `anomaly_report.sh` — готовит JSON/Markdown отчёт.
- `remediate.sh` — promote/abort/scale через `kubectl-argo-rollouts`.
- `notify_telegram.sh` — отправка отчёта в Telegram.

## CI
- `AIOps Weekly` — по расписанию (каждый понедельник) + ручной remediate.

## Практика
- Начинайте с `remediate=none`, смотрите отчёты несколько недель.
- Для безопасных сценариев включайте `promote/abort/scale` на staging.
- Никогда не автоматизируйте remediation в production без change review.
