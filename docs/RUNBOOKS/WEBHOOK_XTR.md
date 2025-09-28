# Runbook: Webhook XTR latency / errors

- **Severity guidance**: SEV-1 при длительном `WebhookP95High` critical или отказе `/telegram/webhook`; SEV-2 при warning >15 мин.
- **Impact**: задержка или потеря Telegram Stars вебхуков → подписки Pro/Pro+/VIP не активируются или истекают с задержкой, клиенты не получают доступ к `alerts` и `news` маршрутам.

## Service Level
- **SLI**: `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/telegram/webhook"}[5m])))`.
- **SLO**: p95 ≤ 1.5 s (см. `docs/SLA_SLO_POLICY.md`).
- **Alerts**: `WebhookP95High` (warning/critical) из `deploy/monitoring/prometheus/alerts.rules.yml`.

## First Response Checklist
1. Подтвердите алерт в Alertmanager (`http://localhost:9093` локально) и убедитесь, что на вас назначена роль Incident Commander.
2. Сообщите в `#newsbot-incident` короткое уведомление: «Investigating Webhook latency alert, status pending». Без PII.

## Диагностика
1. **Контейнеры**: `docker compose -f deploy/compose/docker-compose.yml logs -f app` — ищем задержки в `StarsWebhookHandler` и исключения.
2. **Kubernetes** (prod): `kubectl logs deploy/newsbot-app -c app --since=10m | grep stars-webhook`.
3. **Prometheus проверка**: выполните PromQL из Grafana Explore или `curl`:
   ```bash
   PROM_URL=http://localhost:9090 bash tools/sre/sli_query.sh webhook_p95 5m
   ```
4. **Метрики приложения**: `curl -s http://<app-host>:8080/metrics | grep webhook`
5. **Alertmanager**: убедитесь, что не сработали дополнительные алерты (`Http5xxRateHigh`, `StarsDuplicateRateHigh`).

## Диагностика причин
- Проверить `BillingService.applySuccessfulPayment` (`core/src/main/kotlin/billing/service/BillingService.kt`) на ретраи/idempotency.
- Убедиться, что `StarsWebhookHandler` (`app/src/main/kotlin/billing/StarsWebhookHandler.kt`) не блокируется внешними API.
- Проверить внешние зависимости (Telegram API, база данных) через `docker compose logs db` или `kubectl describe`.
- Просмотреть `observability/Metrics.kt` конфигурацию, чтобы убедиться, что таймеры создаются корректно.

## Действия по восстановлению
1. **Перезапуск воркеров**: `kubectl rollout restart deploy/newsbot-app` или локально `docker compose restart app`.
2. **Idempotency guard**: если замечены дубликаты, включите дополнительный контроль (`BillingService` проверяет `providerPaymentChargeId`). Убедитесь, что счётчики `webhook_stars_duplicate_total` не растут аномально.
3. **Проверка конфигурации**: сверить `telegram.webhookSecret` в `app/src/main/resources/application.conf` и секрет в инфраструктуре.
4. При длительном заторе — временно перевести входящие вебхуки в очередь (описать в тикете, если используется сторонний буфер).

## Rollback / Fix Forward
- **Rollback**: откатить последние изменения в `StarsWebhookHandler` или `App.kt` (маршрут `post("/telegram/webhook")`) через предыдущий стабильный релиз.
- **Fix forward**: задеплоить hotfix с оптимизацией логики биллинга/таймаутов; обязательно добавить тесты (`app/src/test/kotlin/billing/WebhookIntegrationTest.kt`).

## Post Incident
- Заполнить шаблон `docs/TEMPLATES/INCIDENT_TEMPLATE.md` → сохранить в `incidents/`.
- Зафиксировать расход error budget и решение о freeze в соответствии с политикой `docs/SLA_SLO_POLICY.md`.
