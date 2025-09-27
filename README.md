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

## Local Review / Pre-push

Install git hooks and run local revision before pushing:

```bash
./gradlew installGitHooks
bash tools/audit/run_all.sh
bash tools/audit/run_all.sh --skip-build
bash tools/audit/run_all.sh --only-audit
```

`run_all.sh` orchestrates Kotlin lint/tests/build, grep-based audit checks, and optional miniapp verification. The script refuses to run when `APP_PROFILE=prod`. The pre-push hook executes `run_all.sh --skip-build` automatically and blocks the push on failures.

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

## P16 — Bot: Stars/Subscriptions

- Команды бота: `/plans` (активные тарифы и цены в XTR), `/buy` (inline-кнопки PRO/PRO+/VIP), `/status` (текущая подписка).
- UX: выберите план через `/buy`, получите `invoiceLink`, оплатите в Stars, webhook успешного платежа активирует/продлевает подписку, `/status` отражает актуальный уровень.
- Безопасность: логи без токенов, сумм и PII; повторные доставки `update_id` от Telegram не создают дубликаты ответов (in-memory idempotency).
- Smoke: используйте webhook-имитацию из P06-05 для успешного платежа и команду `/status` в чате бота для проверки активации.

## P10 — CSV/Sheets import

### Smoke-curl

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  -F "file=@samples/trades.csv;type=text/csv" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/trades/import/csv" | jq .

curl -s -X POST -H "Authorization: Bearer $JWT" \
  -H "content-type: application/json" \
  -d '{"url":"https://docs.google.com/spreadsheets/d/.../export?format=csv"}' \
  "$BASE/api/portfolio/$PORTFOLIO_ID/trades/import/by-url" | jq .
```

Коды ответов: `200/400/401/413/415/500`. Продажи, которые ведут к отрицательной позиции (SELL>qty), вернутся в `failed` со строкой и причиной.

В Mini App переключитесь во вкладку **Import**, выберите «File» или «By URL». Для CSV-файлов:

1. Загрузите файл (drag & drop или кнопка).
2. Проверьте предпросмотр, при необходимости поменяйте delimiter.
3. Настройте соответствие колонок — все обязательные поля должны быть сопоставлены.
4. После успешной валидации отправьте файл и дождитесь отчёта.

## P14 — Docker & Compose (app + Postgres + Nginx TLS)

### 1) Build образа приложения
```bash
docker build -t newsbot-app:latest .
```

### 2) Подготовка окружения
```bash
cp deploy/compose/.env.example deploy/compose/.env
# отредактируйте TELEGRAM_BOT_TOKEN / TELEGRAM_WEBHOOK_SECRET при необходимости
```

Положите TLS-сертификаты в:

- `deploy/compose/nginx/certs/tls.crt`
- `deploy/compose/nginx/certs/tls.key`

### 3) Запуск docker compose
```bash
cd deploy/compose
docker compose up -d
docker compose ps
```
Ожидаем: db (healthy), затем app (healthy), затем nginx (healthy).

### 4) Health и метрики
```bash
curl -s http://localhost:${NGINX_HTTP_PORT:-8081}/healthz
curl -s http://localhost:${NGINX_HTTP_PORT:-8081}/metrics | head
```

### 5) Настройка вебхука (пример, замените host/secret)
```bash
export BOT_TOKEN="<your_bot_token>"
export PUBLIC_HTTPS="https://<your-domain>:${NGINX_HTTPS_PORT:-8443}"
export WH_SECRET="<your_webhook_secret>"

curl -sG "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
  --data-urlencode "url=${PUBLIC_HTTPS}/telegram/webhook" \
  --data-urlencode "secret_token=${WH_SECRET}" | jq .
```

### 6) Логи и отладка
```bash
docker compose logs -f app
docker compose logs -f nginx
docker compose logs -f db
```

### 7) Остановка/очистка
```bash
docker compose down
docker volume rm deploy_compose_dbdata
```

Security: не храните реальные токены/ключи в репозитории. Используйте переменные окружения/секрет-менеджер.

## P18 — Seed & Demo

Полный smoke-сценарий для не-prod окружений: seeding через SQL + REST, импорт тестовых сделок и проверка портфеля. Не запускайте сидинг на production.

### 1. Подготовка окружения

```bash
export APP_PROFILE=dev
export DATABASE_URL="postgresql://postgres:postgres@localhost:5432/newsbot"
# Альтернатива: DATABASE_USER/DATABASE_PASSWORD/DATABASE_HOST/DATABASE_PORT/DATABASE_NAME
```

PowerShell:

```powershell
$env:APP_PROFILE="dev"
$env:DATABASE_URL="postgresql://postgres:postgres@localhost:5432/newsbot"
```

### 2. Применить demo seed (SQL)

```bash
bash tools/demo/seed.sh
```

Повторные запуски безопасны — используются UPSERT/ON CONFLICT.

### 3. Быстрый REST smoke (если включены demo-роуты)

```bash
curl -s -X POST http://localhost:8080/demo/seed | jq .
curl -s -X POST http://localhost:8080/demo/reset | jq .   # требует DEMO_RESET_ENABLE=true
```

`/demo/seed` вызовет `tools/demo/seed.sh`, `/demo/reset` очистит ключевые таблицы (dev/test only).

### 4. Импорт тестовых сделок

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  -F "file=@miniapp/samples/trades_demo.csv;type=text/csv" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/trades/import/csv" | jq .
```

Файл `miniapp/samples/trades_demo.csv` содержит BUY/SELL по SBER и GAZP, crypto BUY по BTCUSDT, дубликат `ext_id` и SELL с превышением qty (попадёт в `failed`).

### 5. Проверка позиций и оценки

```bash
curl -s -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/positions?sort=instrumentId" | jq .

curl -s -H "Authorization: Bearer $JWT" \
  "$BASE/api/portfolio/$PORTFOLIO_ID/trades?limit=20&offset=0" | jq .
```

Ожидаем: позиции по SBER/GAZP/BTCUSDT с количествами > 0 (кроме SELL > qty, он в failed); trades-репорт покажет вставленные и пропущенные дубликаты.

### 6. Защита от production

- `tools/demo/seed.sh` завершается ошибкой при `APP_PROFILE=prod`.
- Demo-роуты не регистрируются в prod-профиле и требуют явного `DEMO_RESET_ENABLE=true` для `/demo/reset`.

---

## DoD (Definition of Done)
- `docker build` успешен; `docker compose up -d` поднимает `db/app/nginx` (`healthy`).
- `/healthz` и `/metrics` доступны через `nginx`.
- `setWebhook` срабатывает; Ktor получает запросы по HTTPS (TLS терминация в Nginx).  
- Файлы сгенерированы строго в формате для автосоздания PR.
