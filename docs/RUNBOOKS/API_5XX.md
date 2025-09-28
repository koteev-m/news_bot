# Runbook: API 5xx rate spike

- **Severity guidance**: SEV-1 при критическом `Http5xxRateHigh` (>5%) дольше 10 минут или множестве URIs; SEV-2 при warning (2–5%) более 15 минут; SEV-3 для кратковременных отклонений.
- **Impact**: клиенты не могут использовать REST API (`/api/*`), Telegram miniapp и уведомления `alerts` зависают, возможны сбои биллинга.

## Service Level
- **SLI**: `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))`.
- **SLO**: ≤ 2% ошибок 5xx на пятиминутный интервал, ≤5% нарушенных интервалов за 28 дней.
- **Alerts**: `Http5xxRateHigh` warning/critical из `deploy/monitoring/prometheus/alerts.rules.yml`.

## First Response Checklist
1. Подтвердите алерт в Alertmanager и возьмите роль Incident Commander.
2. Зафиксируйте время старта и сообщите в `#newsbot-incident` (без PII): «Investigating elevated 5xx rate, scope under analysis».

## Диагностика
1. **Prometheus**: проверьте общее значение и разбивку по URI:
   ```promql
   topk(5, sum by (uri) (rate(http_server_requests_seconds_count{status=~"5.."}[5m])))
   ```
2. **SRE скрипт**:
   ```bash
   PROM_URL=http://localhost:9090 bash tools/sre/sli_query.sh api_5xx_rate 5m
   ```
3. **Логи**:
   - Локально: `docker compose -f deploy/compose/docker-compose.yml logs -f app | grep "ERROR"`
   - Prod: `kubectl logs deploy/newsbot-app -c app --since=10m`
4. **Трассировки по requestId/traceId**:
   - В логах ищите MDC поля `requestId` (проставляется в `App.kt`).
   - Используйте `rg "requestId=<ID>" -g"*.log" storage/logs` при наличии сохранённых логов.
5. **Горячие маршруты**: `curl -s http://<app-host>:8080/metrics | grep http_server_requests_seconds_count{uri=`

## Диагностика причин
- Проверить базу данных (подключение, миграции) через `docker compose logs db` или `kubectl describe pod <db>`.
- Сравнить с недавними деплоями (`git log --since=2h app/src/main/kotlin`).
- Проверить внешние интеграции (см. `integrations` модуль) на таймауты.
- Оценить, нет ли всплеска трафика из `alerts` (push) или `miniapp`.

## Действия по восстановлению
1. **Mitigation**: включить rate limiting/feature flag на проблемный роут (`App.kt` + `routes/*`).
2. **Rollback**: если последние изменения затронули `App.kt` или `routes`, выполните `kubectl rollout undo deploy/newsbot-app`.
3. **Fix forward**: задеплоить патч с проверками входных данных или повышенными таймаутами.
4. **Stacktrace dump**: при необходимости снять `jstack` (без PII) и приложить к тикету.

## Post Incident
- Обновить статус в публичном канале (если SEV-1) через утверждённый шаблон.
- Заполнить `docs/TEMPLATES/INCIDENT_TEMPLATE.md` и при необходимости пост-мортем.
- Обновить ошибочные запросы/метрики в Grafana, указать влияние на error budget в `docs/SLA_SLO_POLICY.md`.
