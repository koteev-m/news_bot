# Product Analytics / Аналитика продукта

## Events storage / Хранилище событий
- Таблица `events` создаётся миграцией [`V5__analytics_events.sql`][migration].
- Схема: автоинкремент `event_id`, временная метка `ts` (UTC), `user_id` (опционально), `type`, `source`, `session_id`, `props` (`JSONB`).
- Индексы по времени, типу, пользователю и JSONB гарантируют быстрые отчёты по сегментам.
- Ключевые типы событий:
  - `post_published` — публикация поста в канале.
  - `cta_click` — переход по CTA (`/go/{id}`).
  - `miniapp_auth` — успешная валидация initData и выдача JWT.
  - `stars_payment_succeeded` — первичное применение платежа Stars.
  - `stars_payment_duplicate` — повторная доставка платежа.
  - (Расширяемый список, хранится в `props`).

## Where tracked / Где интегрировано
- `news/src/main/kotlin/news/publisher/ChannelPublisher.kt` — событие `post_published` после успешной отправки поста.
- `app/src/main/kotlin/routes/AuthRoutes.kt` → `redirectRoutes` → `app/src/main/kotlin/routes/RedirectRoutes.kt` — события `miniapp_auth` и `cta_click`.
- `app/src/main/kotlin/billing/StarsWebhookHandler.kt` — события `stars_payment_succeeded` / `stars_payment_duplicate`.
- Все события пишутся через `AnalyticsPort` с реализацией [`AnalyticsRepository.kt`][repo-link].

## Run reports / Как запускать отчёты
1. Экспортируйте `DATABASE_URL` в формате `postgres://user:pass@host:port/db` (например, staging через `kubectl port-forward`).
2. Запустите команду локально:
   ```bash
   DATABASE_URL="postgres://user:pass@localhost:5432/newsbot" \
     psql "$DATABASE_URL" -At -F $'\t' -f tools/analytics/daily_report.sql
   ```
3. Вывод содержит:
   - DAU и счётчики ключевых событий за 24 часа.
   - Конверсионную воронку по дням за 7 дней.
   - Недельные когорты миниаппа → платёж.
4. Для staging используйте `$DATABASE_URL` из секретов (например, в CI — `secrets.STAGE_DB_URL`).

## Privacy & Safety / Приватность и безопасность
- В событиях не передаются PII, payload, суммы или токены.
- Допустимые значения: числовые `user_id`, технические идентификаторы (`cluster_key`, `tier`, `id`).
- При добавлении новых полей всегда проверяйте, что они не раскрывают персональные данные.

## Extensibility / Расширение
1. Добавьте новый `type` в место генерации события через `AnalyticsPort` (предпочтительно перечислить его в комментариях миграции и документации).
2. Включите безопасные `props` (строки, числа, булевы значения) без PII.
3. Расширьте отчёты (`tools/analytics/daily_report.sql`) по необходимости.
4. Обновите тесты, проверяющие интеграцию (`storage/src/test/kotlin/repo/AnalyticsRepositoryTest.kt`, `app/src/test/kotlin/analytics/IntegrationEventsTest.kt`).

## Daily export / Ежедневный выгруз (CI)
- Workflow [`analytics-daily.yml`][workflow] запускает отчёт ежедневно в 06:00 UTC и по запросу (`workflow_dispatch`).
- Результат сохраняется артефактом `analytics_daily_report` (retention 7 дней). Секреты не логируются: используется только `${{ secrets.STAGE_DB_URL }}`.

[migration]: ../storage/src/main/resources/db/migration/V5__analytics_events.sql
[repo-link]: ../storage/src/main/kotlin/repo/AnalyticsRepository.kt
[workflow]: ../.github/workflows/analytics-daily.yml
