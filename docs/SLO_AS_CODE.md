# P63 — SLO as Code (Sloth)

## Что это
- SLO спецификации: `slo/*.yaml`
- Генерация Prometheus правил: workflow **SLO Generate (Sloth)**
- Алерты: многооконные burn rate по бюджету ошибок (availability/latency)

## Быстрый старт локально
```bash
docker run --rm -v "$PWD":/work -w /work ghcr.io/slok/sloth:v0.11.0 \
  generate -i slo/api-slo.yaml -o deploy/monitoring/prometheus
```

Использование в Rollouts
- Анализ шаблон `k8s/rollouts/analysis-templates-slo.yaml` использует сгенерированные метрики:
- `slo:<service>:<slo>:error_budget_burn_rate:<window>`
- Если метрики превышают порог — канарь сворачивается/абортируется.
