# Ops

- Проверка правил алертов: `promtool check rules ops/prometheus/alerts.yml`.
- Импорт Grafana дашбордов: в UI Grafana → Dashboards → Import, выбрать файл из `ops/grafana/*.json`.
- Метрика `/metrics` должна отдавать `breaking_publish_latency_seconds_bucket`.
- Документация по `/internal/post_views/sync` и `post_views_total`: `docs/mtproto_views_sync.md`.
- Для вызова internal routes используйте header `X-Internal-Token` и токен из `alerts.internalToken`.
