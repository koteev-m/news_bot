# P79 — Growth Automation 2.0

## Состав
- **Campaigns/Segments/Journeys** — схемы БД, базовый движок.
- **Caps & Quiet Hours** — частотные ограничения, «тихие часы» (UTC).
- **Suppressions** — opt-out per channel/tenant.
- **Messenger** — Telegram (без токенов в коде).
- **Segments** — ClickHouse SQL примеры.

## Оркестратор
- `GrowthEngine.runCampaign()` — пример массовой отправки с проверками капов/тихих часов/опт-аутов.
- Встроить вызовы из админ-панели или cron.

## CI
- `Growth Dry-Run` — проверка компиляции и линт контента.

## Безопасность
- Не храните токены/PII в логах.
- Конфиг переключателей вынесите в `application.conf`/ENV.
