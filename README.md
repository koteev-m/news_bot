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

Быстрые smoke-команды для проверки ключевых REST-ручек локального стенда (`./gradlew :app:run`). Все примеры используют `curl` + `jq` и безопасны для публикации.

### 1. Подготовка окружения

```bash
export BASE="http://localhost:8080"
# JWT появится после успешной проверки initData
```

PowerShell (под Windows):

```powershell
$env:BASE="http://localhost:8080"
```

### 2. Health (публично)

```bash
curl -s "$BASE/health/db" | jq .
# 200, ответ содержит поле "db":"up" (или soft-ok)
```

### 3. Auth: verify initData (публично)

```bash
# Подставьте initData из window.Telegram.WebApp.initData вашего Mini App
curl -s -X POST "$BASE/api/auth/telegram/verify" \
  -H "content-type: application/json" \
  -d '{"initData":"<TELEGRAM_WEBAPP_INITDATA>"}' | jq .

# Ожидаем 200 + { "token":"<JWT>", "expiresAt": "...", "user":{"id": ... } }
# Сохранить JWT:
export JWT="<ПОДСТАВЬТЕ_ЗНАЧЕНИЕ_ИЗ_ОТВЕТА>"
```

Ошибки:

- `400` — некорректное тело/параметры.
- `401` — невалидная подпись `initData` или протухший `auth_date`.

PowerShell:

```powershell
# После успешного ответа выполните
$env:JWT="<ПОДСТАВЬТЕ_ЗНАЧЕНИЕ_ИЗ_ОТВЕТА>"
```

### 4. Quotes (публично)

```bash
curl -s "$BASE/api/quotes/closeOrLast?instrumentId=1&date=2025-09-20" | jq .
# 200 → { "amount":"123.45000000", "ccy":"RUB" }
# 400 → неверные параметры; 404 → нет цены; 500 → внутренняя ошибка
```

### 5. Portfolio (под JWT)

Список:

```bash
curl -s -H "Authorization: Bearer $JWT" "$BASE/api/portfolio" | jq .
# 200 → [] или список
```

Создание:

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  -H "content-type: application/json" \
  -d '{ "name":"My RUB PF", "baseCurrency":"RUB", "valuationMethod":"AVERAGE" }' \
  "$BASE/api/portfolio" | jq .
# 201 → { "id":"<UUID>", ... }
export PORTFOLIO_ID="<ПОДСТАВЬТЕ_UUID_ИЗ_ОТВЕТА>"
```

Ошибки:

- `400` — валидации (валюта/имя).
- `409` — дубль имени у пользователя.

PowerShell:

```powershell
$env:PORTFOLIO_ID="<ПОДСТАВЬТЕ_UUID_ИЗ_ОТВЕТА>"
```

### 6. Positions & Trades (под JWT)

```bash
curl -s -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/positions?sort=instrumentId&order=asc" | jq .
# 200 → массив позиций

curl -s -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/trades?limit=50&offset=0" | jq .
# 200 → { "total":..., "items":[...], "limit":50, "offset":0 }
```

Ошибки:

- `400` — неверные `limit`/`offset`/`uuid`.
- `401` — без JWT.

### 7. Import CSV (multipart, под JWT)

Подготовьте sample-файл (см. `samples/trades.csv` ниже), затем:

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  -F "file=@samples/trades.csv;type=text/csv" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/trades/import/csv" | jq .
# 200 → { "inserted": N, "skippedDuplicates": M, "failed":[...] }
# 415 → неверный MIME; 413 → превышен лимит; 400 → нет части file или неверный UUID
```

### 8. Revalue / Report (под JWT)

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/revalue?date=2025-09-20" | jq .
# 200 → ValuationDailyResponse

curl -s -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/report?from=2025-09-01&to=2025-09-20" | jq .
# 200 → PortfolioReportResponse
```

Ошибки:

- `400` — некорректный диапазон/формат дат.
- `404` — портфель отсутствует.

### 9. Коды и подсказки

- `200/201` — успех; `400` — неверные параметры; `401` — требуется JWT; `404` — не найдено; `409` — конфликт; `413/415` — лимиты/тип; `500` — внутренняя ошибка.
- Для Windows PowerShell замените `export` на `$env:NAME="value"`.
- JWT безопасно хранить только в переменных окружения текущей сессии.
