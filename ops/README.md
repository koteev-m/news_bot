# Ops

- Проверка правил алертов: `promtool check rules ops/prometheus/alerts.yml`.
- При отсутствии promtool локально используйте Docker: `docker run --rm -v "$PWD:/work" -w /work prom/prometheus:latest promtool check rules ops/prometheus/alerts.yml`.
- Импорт Grafana дашбордов: в UI Grafana → Dashboards → Import, выбрать файл из `ops/grafana/*.json`.
- Для импорта через HTTP API нужны утилиты: `jq`, `curl`.
- Метрика `/metrics` должна отдавать `breaking_publish_latency_seconds_bucket`.
- Документация по `/internal/post_views/sync` и `post_views_total`: `docs/mtproto_views_sync.md`.
- Для вызова internal routes используйте header `X-Internal-Token` и токен из `alerts.internalToken`.

## Проверка и импорт

### Prometheus rules

```bash
promtool check rules ops/prometheus/alerts.yml
docker run --rm -v "$PWD:/work" -w /work prom/prometheus:latest promtool check rules ops/prometheus/alerts.yml
```

### Grafana (HTTP API)

```bash
export GRAFANA_URL="https://grafana.example.com"
export GRAFANA_TOKEN="***"
# UID Prometheus datasource в Grafana (см. settings → data sources)
export GRAFANA_DS_UID="XXXXX"
# опционально: id папки (по умолчанию 0 = General)
export GRAFANA_FOLDER_ID="0"

for file in ops/grafana/*.json; do
  payload=$(jq -n --argfile dashboard "$file" --argjson folderId "${GRAFANA_FOLDER_ID:-0}" \
    --arg dsUid "${GRAFANA_DS_UID}" \
    '$dashboard
    | (.templating.list[]? | select(.name == "datasource") | .current.value) = $dsUid
    | (.templating.list[]? | select(.name == "datasource") | .current.text) = "Prometheus"
    | (.. | objects | select(has("datasource")) | .datasource)
      |= (if type == "object" and .uid == "$datasource" then .uid = $dsUid else . end)
    | {dashboard: ., folderId: $folderId, overwrite: true}')
  curl -sS -H "Authorization: Bearer ${GRAFANA_TOKEN}" -H "Content-Type: application/json" \
    -X POST "${GRAFANA_URL}/api/dashboards/db" -d "$payload"
done
```

Подсказки:
- Список папок и их `id`: `curl -sS -H "Authorization: Bearer ${GRAFANA_TOKEN}" "${GRAFANA_URL}/api/folders" | jq`.
- Проверка результата: `curl -sS -H "Authorization: Bearer ${GRAFANA_TOKEN}" "${GRAFANA_URL}/api/search?query=Funnel%20%26%20Growth" | jq`.
