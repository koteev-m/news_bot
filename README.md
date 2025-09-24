# News Bot Monorepo

Skeleton Kotlin monorepo using Gradle with the following modules:

- `:app` – Ktor application entry point
- `:core`
- `:integrations` – external services (MOEX/CG/CBR)
- `:bot`
- `:news`
- `:alerts`
- `:storage` – Exposed ORM and Flyway migrations
- `:tests`

## Development

Use Java 21. To list projects:

```bash
./gradlew -q projects
```

## Environment

Copy `.env.example` to `.env` and provide the required values.

## P05 — API smoke

Use these smoke commands against a locally running instance (`./gradlew :app:run`):

Auth (Mini App verify):

```bash
curl -sX POST http://localhost:8080/api/auth/telegram/verify \
  -H 'content-type: application/json' \
  -d '{"initData":"<Telegram.WebApp.initData>"}' | jq .
```

Expected: HTTP 200 with a token for valid `initData`; otherwise 400/401.

Quotes (public):

```bash
curl -s 'http://localhost:8080/api/quotes/closeOrLast?instrumentId=1&date=2025-09-20' | jq .
```

Portfolio (JWT protected):

```bash
export JWT='<paste_test_jwt>'
curl -s -H "Authorization: Bearer $JWT" http://localhost:8080/api/portfolio | jq .
```

Import CSV (multipart):

```bash
curl -s -X POST "http://localhost:8080/api/portfolio/<id>/trades/import/csv" \
  -H "Authorization: Bearer $JWT" \
  -F "file=@samples/trades.csv;type=text/csv" | jq .
```

Revalue / Report (JWT protected):

```bash
curl -s -H "Authorization: Bearer $JWT" \
  -X POST "http://localhost:8080/api/portfolio/<id>/revalue?date=2025-09-20" | jq .

curl -s -H "Authorization: Bearer $JWT" \
  "http://localhost:8080/api/portfolio/<id>/report?from=2025-09-01&to=2025-09-20" | jq .
```
