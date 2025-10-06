# Support & Help Center / Центр поддержки

## API / API
- `GET /api/support/faq/{locale}` — returns FAQ articles for a locale (e.g. `en`, `ru`). Empty array is valid when there are no articles.
- `POST /api/support/feedback` — accepts JSON payload with `category`, `subject`, `message`, optional `appVersion`, `deviceInfo`, `locale`. Responds with `202 Accepted` and `{ "ticketId": <number> }` on success.

Request example:
```bash
curl -s -X POST \
  -H "content-type: application/json" \
  "$BASE/api/support/feedback" \
  -d '{"category":"bug","subject":"Crash","message":"Chart freezes"}'
```

## Admin / Администрирование
- `GET /api/admin/support/tickets?status=OPEN&limit=200` — list tickets ordered by `ts` desc. Requires JWT for a user whose id is in `admin.adminUserIds`.
- `PATCH /api/admin/support/tickets/{id}/status` with body `{ "status": "ACK" }` — updates status to one of `OPEN`, `ACK`, `RESOLVED`, `REJECTED`.
- Admin API is protected by the standard JWT guard; non-admins receive `403`.

## Rate limit / Ограничение скорости
- Per-subject token bucket (user id if authenticated, otherwise IP/host).
- Default settings from `application.conf`:
  ```hocon
  support.rateLimit.capacity = 5
  support.rateLimit.refillPerMinute = 5
  ```
- When exhausted, the API returns `429 Too Many Requests` and a `Retry-After` header.

## Analytics / Аналитика
- On successful feedback submission we emit `support_feedback_submitted` via `AnalyticsPort`.
- Payload includes `ticket_id`, `category`, `locale`, and optional `userId` if authenticated.
- Hook events into downstream dashboards for weekly telemetry.

## PII policy / Политика обработки данных
- Inputs are trimmed and length-limited (subject ≤ 120 chars, message ≤ 4000).
- No automatic logging of free-text fields; analytics only receives normalized metadata.
- Encourage users (UI copy) to avoid personal data. Stored fields are intended for anonymised product feedback.

## Managing FAQ / Работа с FAQ
- Seed or update FAQ via Flyway migrations or admin SQL scripts:
  ```sql
  INSERT INTO support_faq(locale, slug, title, body_md)
  VALUES ('en', 'getting-started', 'Getting started', 'Welcome! Use the import wizard...')
  ON CONFLICT (locale, slug) DO UPDATE
  SET title = EXCLUDED.title,
      body_md = EXCLUDED.body_md,
      updated_at = now();
  ```
- For occasional edits, an internal admin UI can reuse the `SupportRepository.upsertFaq` method.

## FAQ example / Пример статьи
```json
{
  "locale": "en",
  "slug": "import-csv",
  "title": "How do I import a CSV?",
  "bodyMd": "1. Open Import → CSV.\n2. Upload UTF-8 file.\n3. Map columns and confirm.",
  "updatedAt": "2024-05-20T10:30:00Z"
}
```
