# P38 — Growth & Funnels

## UTM и рефералы / UTM & Referrals
- Поддерживаемые параметры: `utm_source`, `utm_medium`, `utm_campaign`, а также `ref` (буквенно-цифровой код) и `cta` (идентификатор кнопки/лендинга).
- Каждый `ref` привязан к `referrals.ref_code` и владельцу (`owner_user_id`). Код уникальный, рекомендованный формат — 5–8 символов `[A-Z0-9]`.
- При первом переходе `/go/{id}` сохраняем визит в `referral_visits` с UTM-метками и, если доступно, `cta_id`. После авторизации /start Telegram — вызываем `attachUser`, чтобы дополнить визит `tg_user_id` (идемпотентно по паре `ref_code`, `tg_user_id`).
- Событие `cta_click` в `events` фиксирует идентификатор CTA и UTM-метки для аналитики.

## Deep-link payload (≤64 bytes)
- Шаблон `id=<id>|src=<source>|med=<medium>|cmp=<campaign>|cta=<cta>|ref=<code>|ab=<variant>` — маркеры добавляются только при наличии данных.
- Payload обрезается по байтам (UTF-8) до лимита, затем URL-энкодится в `?start=`. Значения безопасно нормализуются (латиница/цифры/`-_.:`).
- Базовый URL берётся из `news.botDeepLinkBase`, по умолчанию `https://t.me/<bot_username>`.

## Эксперименты / Experiments
- Конфигурация хранится в `experiments` (`traffic` в процентах, сумма = 100). Админ-API: `POST /api/admin/experiments/upsert` (JWT + `adminUserIds`).
- Назначение варианта детерминировано: `hash(userId + key) mod 100`, persistent storage `experiment_assignments`. Повторное назначение возвращает сохранённый вариант.
- Клиентский API: `GET /api/experiments/assignments` (JWT) → список активных экспериментальных вариантов пользователя.
- Mini App читает ассайнменты и отображает их в настройках (read-only).

## SQL отчёты
- Файл `tools/analytics/funnels.sql` содержит три запроса:
  1. Воронка Post → Click → Start → Pay (скользящее окно 7 дней).
  2. Производительность рефералов (визиты и привязанные пользователи за 7 дней).
  3. Лифт A/B по эксперименту `cta_copy` (клики и оплаты за 14 дней).
- Запуск: `psql $DATABASE_URL -f tools/analytics/funnels.sql` либо через BI-инструмент с доступом к продовой БД.

## Privacy / Конфиденциальность
- События не содержат PII: храним только числовые идентификаторы (`user_id`, `ref_code`, UTM). Параметры/токены не логируются.
- Сроки хранения задаются в `privacy.retention` (см. P36). Регулярно запускаем процессы очистки, чтобы соответствовать требованиям.
