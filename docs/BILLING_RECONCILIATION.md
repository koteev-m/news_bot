# Billing reconciliation / Сверка биллинга

## Инварианты / Invariants
- **APPLY duplicates forbidden / Дубликаты APPLY запрещены.** Каждая запись `billing_ledger.event = 'APPLY'` должна быть уникальна по `provider_payment_id`.
- **Active subscription per APPLY / Активная подписка на каждую APPLY.** После применения платежа статус подписки `ACTIVE` соответствует пользователю и тарифу.
- **DUPLICATE is informational / DUPLICATE только для фиксации.** Записи `DUPLICATE` не продлевают подписку и не изменяют баланс.

## Где лежит леджер / Ledger location
- PostgreSQL таблица `billing_ledger` (создаётся Flyway миграцией `V8__billing_ledger_recon.sql`).
- Данные неизменяемы: записи добавляются через `BillingLedgerPort.append` из `BillingService`.
- Дубликаты и результаты сверок находятся в таблицах `billing_recon_runs` и `billing_recon_mismatches`.

## Как смотреть последние сверки / Inspect recent runs
```sql
SELECT run_id, started_at, finished_at, status, notes
FROM billing_recon_runs
ORDER BY started_at DESC
LIMIT 20;
```
- Детализацию расхождений можно получить по `run_id`:
```sql
SELECT kind, user_id, provider_payment_id, tier, created_at
FROM billing_recon_mismatches
WHERE run_id = $1
ORDER BY created_at DESC;
```

## CI отчёт / CI report artifact
- GitHub Actions workflow **billing-recon** запускается ежедневно в 03:15 UTC и вручную через `workflow_dispatch`.
- В рамках job выполняется `./gradlew :app:runRecon`, stdout и файл `recon_result.txt` содержат итог (`runId=… status=… counts=…`).
- Артефакт **billing-recon-result** доступен на странице workflow в разделе Artifacts, содержит `recon_result.txt`.

## Реакция на WARN/FAIL / Incident response
- **WARN:** проверить `billing_recon_mismatches` для соответствующего `run_id`, устранить причину (например, задвоенный `provider_payment_id`), при необходимости вручную скорректировать подписку и перезапустить сверку.
- **FAIL:** сверка остановилась — отменить последние опасные изменения (rollback), зафиксировать инцидент, создать тикет в платформенной очереди и провести ручную коррекцию после восстановления.
- Во всех случаях исключить попадание PII и сумм в логи, использовать только идентификаторы.

## Локальный запуск / Local run
```bash
./gradlew :app:runRecon
```
- Перед запуском задать `DATABASE_URL` на dev/staging экземпляр Postgres.
