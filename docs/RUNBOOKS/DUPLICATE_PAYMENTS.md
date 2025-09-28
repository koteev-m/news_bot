# Runbook: Duplicate Stars payments

- **Severity guidance**: SEV-1 при `StarsDuplicateRateHigh` critical (>2%) >10 мин; SEV-2 при warning (1–2%); SEV-3 при разовых всплесках.
- **Impact**: клиенты видят двойные списания или продления подписки; финансовые и compliance риски.

## Service Level
- **SLI**: `increase(webhook_stars_duplicate_total[5m]) / clamp_min(increase(webhook_stars_success_total[5m]), 1)`.
- **SLO**: ≤1% дубликатов, допускается ≤5% нарушенных интервалов за 28 дней.
- **Alerts**: `StarsDuplicateRateHigh` из `deploy/monitoring/prometheus/alerts.rules.yml`.

## First Response Checklist
1. Подтвердите алерт в Alertmanager, зафиксируйте время начала.
2. Сообщите Incident Commander и Billing owner в `#newsbot-billing`.
3. Заморозьте автодеплой биллинга до завершения расследования.

## Диагностика
1. **Prometheus**: проверьте тренд успехов и дубликатов:
   ```promql
   increase(webhook_stars_success_total{job="app"}[5m])
   increase(webhook_stars_duplicate_total{job="app"}[5m])
   ```
2. **Логи StarsWebhookHandler**: `docker compose logs -f app | grep "stars-webhook"` или `kubectl logs ... --since=15m`.
3. **База данных**: запросите последние платежи, убедитесь, что `provider_payment_charge_id` уникален.
4. **Скрипт SLI**:
   ```bash
   PROM_URL=http://localhost:9090 bash tools/sre/sli_query.sh stars_duplicate_rate 5m
   ```

## Проверки
- Убедиться, что `BillingService.applySuccessfulPayment` сохраняет `providerPaymentChargeId` и отвергает дубликаты (`core/src/main/kotlin/billing/service/BillingService.kt`).
- Проверить идемпотентность webhook в `app/src/main/kotlin/billing/StarsWebhookHandler.kt` (сравнение `providerPaymentChargeId`).
- Осмотреть журналы `alerts_budget_reject_total` на предмет лимитов, если клиенты повторяют оплату из-за отклонений.

## Действия по восстановлению
1. **Временный guard**: включить дополнительную проверку в инфраструктуре (например, фильтр на стороне Telegram Bot API, если доступно).
2. **Компенсации**: сформировать список затронутых пользователей (ID без PII) и передать в финансы для возврата.
3. **Fix forward**: задеплоить патч, усиливающий уникальность (например, использование `providerPaymentChargeId` + `userId`).
4. **Rollback**: при необходимости откатить изменения в `BillingService`/`StarsWebhookHandler`.

## Пост-инцидент
- Обновить клиентов через статус-канал (без раскрытия персональных данных).
- Заполнить шаблон инцидента и пост-мортема (`docs/TEMPLATES`).
- Обновить error budget отчёт, указать реальное влияние.
