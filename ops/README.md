# Ops

- Проверка правил алертов: `promtool check rules ops/prometheus/alerts.yml`.
- Импорт Grafana дашбордов: в UI Grafana → Dashboards → Import, выбрать файл из `ops/grafana/*.json`.
- Метрика `/metrics` должна отдавать `breaking_publish_latency_seconds_bucket`.
