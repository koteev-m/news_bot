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

Быстрые smoke-команды для проверки ключевых REST-ручек локального стенда (`./gradlew :app:run`). Все примеры используют `curl`
и `jq`, безопасны для публикации и сохраняют токены только в переменных окружения.

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
# 200, содержит "db":"up" (или soft-ok)
```

### 3. Auth: verify initData (публично)

```bash
# Подставьте initData из window.Telegram.WebApp.initData
curl -s -X POST "$BASE/api/auth/telegram/verify" \
  -H 'content-type: application/json' \
  -d '{"initData":"<TELEGRAM_WEBAPP_INITDATA>"}' | jq .

# Ожидаем 200 + {"token":"<JWT>","expiresAt":"...","user":{"id":...}}
export JWT="<ВСТАВИТЕ_JWT_ИЗ_ОТВЕТА>"
```

Ошибки: `400` (битое тело), `401` (невалидный hash/протухший auth_date).

PowerShell:

```powershell
$env:JWT="<ВСТАВИТЕ_JWT_ИЗ_ОТВЕТА>"
```

### 4. Quotes (публично)

```bash
curl -s "$BASE/api/quotes/closeOrLast?instrumentId=1&date=2025-09-20" | jq .
# 200 → {"amount":"123.45000000","ccy":"RUB"}
# 400 → неверные параметры; 404 → нет цены; 500 → внутренняя ошибка
```

### 5. Portfolio (требуется JWT)

Список:

```bash
curl -s -H "Authorization: Bearer $JWT" "$BASE/api/portfolio" | jq .
# 200 → [] или список портфелей
```

Создание:

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  -H 'content-type: application/json' \
  -d '{ "name":"My RUB PF", "baseCurrency":"RUB", "valuationMethod":"AVERAGE" }' \
  "$BASE/api/portfolio" | jq .
# 201 → {"id":"<UUID>", ...}
export PORTFOLIO_ID="<UUID_ИЗ_ОТВЕТА>"
```

Ошибки: `400` (валидации), `409` (дубликат имени).

PowerShell:

```powershell
$env:PORTFOLIO_ID="<UUID_ИЗ_ОТВЕТА>"
```

### 6. Positions & Trades (требуется JWT)

```bash
curl -s -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/positions?sort=instrumentId&order=asc" | jq .

curl -s -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/trades?limit=50&offset=0" | jq .
# 200 → {"total":...,"items":[...],"limit":50,"offset":0}
# 400 → неверные параметры; 401 → без JWT
```

### 7. Import CSV (multipart, требуется JWT)

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  -F "file=@samples/trades.csv;type=text/csv" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/trades/import/csv" | jq .
# 200 → {"inserted":N,"skippedDuplicates":M,"failed":[...]}
# 415 → плохой MIME; 413 → превышен лимит; 400 → нет части "file"/битый UUID
```

### 8. Revalue / Report (требуется JWT)

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/revalue?date=2025-09-20" | jq .

curl -s -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/report?from=2025-09-01&to=2025-09-20" | jq .
# 200 → агрегаты; 400 → неверный диапазон; 404 → портфель отсутствует
```

### 9. Коды и подсказки

- `200/201` — успех; `400` — неверные параметры; `401` — требуется JWT; `404` — не найдено;
  `409` — конфликт; `413/415` — лимит/тип; `500` — внутренняя ошибка.
- PowerShell: `export` замените на `$env:NAME="value"`.
- Используйте `| jq .` для форматирования JSON в терминале.

## P06 — Billing/Stars smoke

### Планы (публично)
```bash
export BASE="http://localhost:8080"
curl -s "$BASE/api/billing/plans" | jq .
# 200 → [{"tier":"PRO","title":"…","priceXtr":1234,"isActive":true}, ...]

Создать инвойс Stars (JWT)

export JWT="<PASTE_JWT_FROM_P05_AUTH_VERIFY>"
curl -s -X POST -H "Authorization: Bearer $JWT" \
  -H "content-type: application/json" \
  -d '{ "tier":"PRO" }' \
  "$BASE/api/billing/stars/invoice" | jq .
# 201 → {"invoiceLink":"https://t.me/..."}
# 400 → неверный tier/нет плана; 401 → без JWT

Мой статус подписки (JWT)

curl -s -H "Authorization: Bearer $JWT" "$BASE/api/billing/stars/me" | jq .
# 200 → {"userId":..., "tier":"PRO", "status":"ACTIVE", ...} или FREE по умолчанию

Webhook успешного платежа (DEV-симуляция)

# Только при локальном тесте, с секретом вебхука:
export WH_SECRET="test_webhook_secret"  # ваш X-Telegram-Bot-Api-Secret-Token
curl -s -X POST "$BASE/telegram/webhook" \
  -H "X-Telegram-Bot-Api-Secret-Token: $WH_SECRET" \
  -H "content-type: application/json" \
  -d '{
    "message": {
      "from": {"id": 7446417641},
      "successful_payment": {
        "currency": "XTR",
        "total_amount": 1234,
        "invoice_payload": "7446417641:PRO:abc123",
        "provider_payment_charge_id": "pmt_demo_1"
      }
    }
  }'
# 200 (быстрый ACK). Повторная отправка того же JSON → 200 (идемпотентность).

PowerShell: замените export на $env:NAME="value".
```
