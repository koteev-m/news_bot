# P68 — OpenTelemetry Collector + FinOps dashboards

- Collector (helm): `helm/otel-collector/values.yaml` — вход OTLP (4317/4318), экспорт в Tempo/Loki/Prometheus.
- Grafana: `golden-signals-and-cost.json`.
- CI Gate: `observability-gate.yml` — проверяет, что метрики/трейсы/логи доступны.

Подключение приложения:
- Ktor app отправляет трассы/метрики/логи в OTLP (см. P50).
- Mini App может посылать логи/виталс через OTLP HTTP `/v1/logs`/`/v1/metrics` (по желанию).
