# Runbook: Alert noise reduction

- **Scenario**: множественные повторяющиеся уведомления `WebhookP95High`, `Http5xxRateHigh`, `StarsDuplicateRateHigh` без фактической деградации.
- **Impact**: усталость on-call, пропущенные важные события, рост MTTA.

## Диагностика
1. Проверить историю алертов в Alertmanager (route `webhook`).
2. Изучить корреляцию с реальными метриками на Grafana dashboard `NewsBot / Observability`.
3. Сверить настройки в `deploy/monitoring/prometheus/alerts.rules.yml` (пороги, `for` интервал).
4. Проверить настройки `alerts` модуля (ограничения бюджета пушей `alerts_budget_reject_total`).

## Мгновенные действия
1. **Quiet hours / Mute**: если совпадает с плановым окном, установите временный mute rule в Alertmanager (≤2 часа, согласовано с Incident Commander).
2. **Hysteresis**: увеличьте `for:` в нужных правилах (например, с `5m` до `10m`) и задеплойте обновлённый `alerts.rules.yml` через `kubectl apply -f`.
3. **Budget tuning**: скорректируйте лимиты в конфиге `alerts` сервиса (см. `routes/AlertsSettingsRoutes.kt`) и задокументируйте изменение.
4. **API Patch**: для тарифов Pro+ доступен `PATCH /api/alerts/settings` — временно увеличьте cooldown и зафиксируйте изменение в тикете.

## Проверки безопасности / compliance
- Исключить публикацию PII в уведомлениях; используйте агрегированные данные.
- Убедиться, что настройки и токены не попадают в репозитории/чаты.

## Откат
- Верните предыдущие значения hysteresis и quiet hours после подтверждения стабильности.
- Отключите временные mute правила.

## Пост-действия
- Обновите `docs/SLA_SLO_POLICY.md`, если изменения постоянные.
- Добавьте Lessons Learned в пост-мортем при частых шумовых инцидентах.
