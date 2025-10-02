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

## P30 — Secrets & ENV

- Store local secrets in `.env` files (root or service-specific) that are ignored by Git. Use GitHub Secrets for CI/CD (`STAGE_*` for load tests) and Vault/SOPS for production delivery.
- Provision runtime variables for compose by copying the template and starting the stack:
  ```bash
  cp deploy/compose/.env.example deploy/compose/.env
  docker compose -f deploy/compose/docker-compose.yml up -d
  ```
- Configure load nightly workflows exclusively through GitHub Secrets (`STAGE_BASE_URL`, `STAGE_JWT`, `STAGE_WEBHOOK_SECRET`, `STAGE_TG_USER_ID`). No plaintext values in the repository or workflow logs.
- The miniapp keeps JWT tokens only in memory (e.g., React state) — never persist them in `localStorage`/`sessionStorage`.
- Detailed policy: [docs/SECRETS_POLICY.md](docs/SECRETS_POLICY.md).
- Run grep-based leak checks with [tools/audit/grep_checks.sh](tools/audit/grep_checks.sh).

## P31 — Backups & DR

```bash
# локальный бэкап (dev/test)
APP_PROFILE=dev DATABASE_URL=postgres://user:pass@localhost:5432/app \
bash tools/db/backup.sh

# восстановление (на НЕ-prod)
I_UNDERSTAND=1 APP_PROFILE=staging \
bash tools/db/restore.sh --dump backups/20250101_023000/db.dump --target-url postgres://user:pass@host:5432/db
```

- Полный план: [docs/DR_PLAN.md](docs/DR_PLAN.md).
- Ночной CI-бэкап: [.github/workflows/db-backup.yml](.github/workflows/db-backup.yml) — артефакты доступны 7 дней на странице Actions (Releases → Artifacts).
- Скрипты `tools/db/backup.sh` и `tools/db/restore.sh` с защитой `APP_PROFILE=prod` предназначены для локальных/staging работ.

## P32 — Feature-flags & Admin

```hocon
admin {
  adminUserIds = [ 7446417641 ]  # Telegram user_id c правом PATCH
}

features {
  importByUrl  = false
  webhookQueue = true
  newsPublish  = true
  alertsEngine = true
  billingStars = true
  miniApp      = true
}
```

```bash
# read (любой аутентифицированный пользователь / any authenticated user)
curl -s -H "Authorization: Bearer $JWT" $BASE/api/admin/features | jq .

# patch (admin only)
curl -s -X PATCH -H "Authorization: Bearer $JWT" -H "content-type: application/json" \
  -d '{ "importByUrl": true }' $BASE/api/admin/features -i
```

- Тогглы обновляются мгновенно: `FeatureFlagsService.updatesFlow` bump'ится после каждой успешной правки, сервисы (alerts/news/webhook/import) считывают обновлённое состояние без рестарта.
- PATCH доступен только аккаунтам из `admin.adminUserIds`; для поиска `user_id` возьмите значение из Telegram (например, `/my_id` в @userinfobot или payload от Stars-платежей).
- Ответы компактные JSON без PII, ошибки 401/403/400 покрывают неаутентифицированные/неадминские/битые запросы.

## P38 — Growth & Funnels

Документация: [docs/GROWTH_FUNNELS.md](docs/GROWTH_FUNNELS.md).

```bash
# Админ: создать/обновить эксперимент
curl -s -X POST -H "Authorization: Bearer $JWT" -H "content-type: application/json" \
  -d '{ "key":"cta_copy", "enabled":true, "traffic":{"A":50,"B":50} }' \
  "$BASE/api/admin/experiments/upsert" -i

# Клиент: получить свои варианты
curl -s -H "Authorization: Bearer $JWT" "$BASE/api/experiments/assignments" | jq .

# Redirect с UTM/ref/cta
curl -I "$BASE/go/news?utm_source=channel&utm_medium=cta&utm_campaign=oct&ref=R7G5K2&cta=promo"
# → 302 Location: https://t.me/<bot>?start=id=news|src=channel|med=cta|cmp=oct|ref=R7G5K2|cta=promo|ab=<variant>
```

## P34 — Blue/Green & Canary

```bash
# запуск стека
docker compose -f deploy/compose/docker-compose.bluegreen.yml up -d

# canary 10% на green
bash tools/release/switch_traffic.sh GREEN 10

# полный перевод на green
bash tools/release/switch_traffic.sh GREEN 0

# включить/выключить maintenance
bash tools/release/maintenance.sh on
bash tools/release/maintenance.sh off
```

- Заметка: health через /healthz, метрики через /metrics.

## P35 — Security hardening & pen-test

- Edge + app security headers: CSP (`default-src 'none'`), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy: camera=(), microphone=(), geolocation=()`, HSTS enabled for production profiles only.
- Dual-layer global rate-limits: Nginx `limit_req` (120 req/min per IP, burst 40) and Ktor token bucket (per user/IP, 60 req/min, burst 20) with `429` + `Retry-After` responses.
- Dual-key JWT rotation: primary signs, secondary verifies for seamless rollout. Secrets live in `security.jwtSecretPrimary` / `security.jwtSecretSecondary`.

### OWASP ZAP baseline

- GitHub Actions → **ZAP Baseline Scan** (`.github/workflows/zap-baseline.yml`). Trigger manually via `workflow_dispatch` or wait for the nightly schedule. The workflow targets `${{ secrets.STAGE_BASE_URL }}` and uploads the HTML report as an artifact.

### Rotation helpers

```bash
bash tools/security/rotate_jwt_secret.sh "<new_primary>"
bash tools/security/rotate_webhook_secret.sh "<new_secret>"
```

- Detailed guidance: [docs/SECURITY_HARDENING.md](docs/SECURITY_HARDENING.md) and [docs/ROTATION_RUNBOOK.md](docs/ROTATION_RUNBOOK.md).

## P27 — Integrations hardening

## P28 — Metrics wiring

- `webhook_stars_success_total` — увеличивается после успешной первичной обработки XTR-платежа в `StarsWebhookHandler` и фоновой очереди `processStarsPayment` (`app/src/main/kotlin/billing/StarsWebhookHandler.kt`, `app/src/main/kotlin/App.kt`).
- `webhook_stars_duplicate_total` — инкрементируется для идемпотентных повторов в тех же обработчиках, когда `BillingService` сообщает `duplicate=true`.
- `alerts_push_total` — счётчик успешных пушей в алерт-движке (`alerts/src/main/kotlin/alerts/engine/AlertEngine.kt`).
- `alerts_budget_reject_total` — фиксирует блокировки анти-шума (budget/quiet/cooldown) в алерт-движке.
- `news_publish_total` — пополнение канала новостей через `ChannelPublisher` (`news/src/main/kotlin/news/publisher/ChannelPublisher.kt`).


Интеграционные клиенты (MOEX, CoinGecko, CBR) работают через единый `HttpClient` с гибкой конфигурацией в `application.conf`.

```
integrations.http {
  userAgent = "newsbot-integrations/1.0"
  timeoutMs { connect = …, socket = …, request = … }
  retry {
    maxAttempts = …
    baseBackoffMs = …
    jitterMs = …
    respectRetryAfter = true|false
    retryOn = [429, 500, 502, 503, 504]
  }
  circuitBreaker {
    failuresThreshold = …
    windowSeconds = …
    openSeconds = …
    halfOpenMaxCalls = …
  }
}
```

- Таймауты на соединение, чтение и всю заявку задаются в миллисекундах и применяются ко всем интеграциям.
- Ретраи покрывают HTTP 429/5xx и сетевые ошибки, используют экспоненциальный backoff с джиттером и уважают `Retry-After` (секунды или HTTP-date).
- Circuit breaker фиксирует окно ошибок, поддерживает `OPEN` → `HALF_OPEN` → `CLOSED`, учитывает параллельные half-open пробы.
- Метрики Micrometer: `integrations_retry_total`, `integrations_retry_after_honored_total`, `integrations_cb_state`, `integrations_cb_open_total`, `integrations_request_seconds{service, outcome}` доступны по `/metrics`.
- Клиенты MOEX/CoinGecko/CBR используют общий клиент и локальную реализацию CB — внешние «тяжёлые» библиотеки (resilience4j и др.) не подключаются.

## P29 — Release pipeline

```bash
bash tools/release/bump_version.sh 1.2.0
git push origin v1.2.0
```

The GitHub Actions workflow publishes GitHub Releases and pushes Docker images to `ghcr.io/<owner>/<repo>` using the built-in `GITHUB_TOKEN`. No additional secrets are required for the default pipeline. When working with a personal container registry, define a `CR_PAT` secret for local usage, but the workflow itself continues to rely on `GITHUB_TOKEN` for authentication.

Release artifacts (application bundle plus deployment helpers) are attached to the GitHub Releases page, and container images are available under the repository package feed in GHCR.

When triggering `workflow_dispatch`, provide the desired `version` input in SemVer format (e.g. `1.2.0`).

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

## P26 — Webhook queue

Очередь вебхука Telegram построена на ограниченном `Channel` с конфигурируемой вместимостью (`webhook.queue.capacity`), количеством воркеров (`webhook.queue.workers`) и стратегией переполнения (`webhook.queue.overflow` — `drop_oldest` или `drop_latest`). Маршрут `/telegram/webhook` читает тело один раз, мгновенно отвечает `200 OK` и передаёт `TgUpdate` в очередь, поэтому обработка платежей идёт в фоновых корутинах с корректным завершением на shutdown.

Метрики очереди экспортируются в `/metrics` и включают `webhook_queue_size` (текущий размер), `webhook_queue_dropped_total` (дропы из-за переполнения), `webhook_queue_processed_total` (успешно обработанные элементы) и `webhook_handle_seconds` (таймер длительности обработчика).

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

## P25 — Import by-URL: feature-flag & rate-limit

Toggle and configure the CSV import via URL guard with HOCON:

```hocon
import {
  byUrlEnabled = true
  byUrlRateLimit { capacity = 3, refillPerMinute = 3 }
}
```

- `503 Service Unavailable` + `{ "error":"by_url_disabled" }` when the feature flag is off.
- `429 Too Many Requests` + `Retry-After` header and `{ "error":"rate_limited" }` when the per-subject bucket is empty.

## P24 — Nightly soak

Ночной soak-тесты выполняются GitHub Actions workflow [`Nightly Load Soak`](.github/workflows/load-nightly.yml) по расписанию `0 2 * * *` (02:00 UTC) и вручную через `workflow_dispatch`. Для прогона нужны секреты окружения:

- `STAGE_BASE_URL` — базовый URL стейджинга;
- `STAGE_JWT` — JWT для портфельных запросов (опционально `STAGE_PORTFOLIO_ID`);
- `STAGE_WEBHOOK_SECRET` — Telegram webhook secret;
- `STAGE_TG_USER_ID` — Telegram user id для успешного платежа.

Оба job'а выгружают артефакты `summary.json` и `junit.xml` (портфель и вебхук) через `Actions → Nightly Load Soak → <run> → Artifacts`.
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

## P21 — Monitoring (Prometheus + Grafana + Alertmanager)

### 1) Поднять app/db/nginx (P14)

```bash
docker compose -f deploy/compose/docker-compose.yml up -d
```

### 2) Поднять monitoring (в той же сети compose_default)

```bash
cd deploy/monitoring
cp .env.example .env
docker compose -f docker-compose.monitoring.yml up -d
```

### 3) Открыть Grafana

Откройте http://localhost:3000 и авторизуйтесь с `admin / $GF_SECURITY_ADMIN_PASSWORD`.

### 4) Dashboard

Дашборд "NewsBot / Observability" импортируется автоматически.

### 5) Alerts

Проверьте состояние алёртов в Prometheus: http://localhost:9090/alerts.

### Примечания

- Сеть `compose_default` должна существовать (поднимайте app-compose перед monitoring).
- В бою меняйте пароли и webhook-URL через переменные окружения или секрет-менеджер.
- Метрики webhook/duplicates требуют инкремента соответствующих счётчиков в коде (если ещё не сделано).

## P22 — SLA/SLO & Runbooks

### Ресурсы
- `docs/SLA_SLO_POLICY.md` — политика SLA/SLO/SLI, error budget, связи с `deploy/monitoring/prometheus/alerts.rules.yml`.
- `docs/INCIDENT_RESPONSE.md` — роли, матрица эскалации, шаблоны коммуникаций.
- `docs/RUNBOOKS/` — операционные инструкции для webhook latency, HTTP 5xx, дубликатов платежей, DoS на импорт и шумных алертов.
- `docs/TEMPLATES/` — шаблоны инцидентов и пост-мортемов.
- `docs/ONCALL_ROTATION.md` — расписание on-call и handoff чек-лист.

### Быстрые команды
```bash
# Новый инцидент (каркас)
bash tools/sre/incident_new.sh "webhook-xtr-latency"
# Запрос SLI к Prometheus
PROM_URL=http://localhost:9090 bash tools/sre/sli_query.sh webhook_p95 5m
```

### Monitoring Stack
- Используйте инструкции раздела P21 (выше) для запуска Prometheus/Grafana (`deploy/monitoring/docker-compose.monitoring.yml`).
- Дашборд: Grafana → "NewsBot / Observability" (включает метрики SLO).

## P23 — Load testing (k6/JMeter)

k6 сценарии находятся в `deploy/load/k6`. Перед запуском скопируйте пример окружения и заполните значения:

```bash
cp deploy/load/.env.example deploy/load/.env
export $(grep -v '^#' deploy/load/.env | xargs)
# smoke
k6 run deploy/load/k6/portfolio_scenario.js
k6 run deploy/load/k6/webhook_scenario.js
# or via runner
bash tools/load/run_k6.sh portfolio:smoke
bash tools/load/run_k6.sh webhook:burst
```

Раннер и скрипты проверяют `APP_PROFILE`. При значении `prod` выполнение прерывается, что защищает боевой контур. Для коротких проверок (например, в CI) можно задать `K6_SCENARIO=smoke K6_SHORT_RUN=true K6_DRY_RUN=true` — сценарии выполнятся в dry-run без реальных запросов.

В каталоге `deploy/load/jmeter/` расположен план `portfolio_plan.jmx`. Откройте его в JMeter GUI, задайте переменные (`BASE_URL`, `JWT`, `PORTFOLIO_ID`) и запустите Thread Group. Для non-GUI режима сохраните план и выполните `jmeter -n -t deploy/load/jmeter/portfolio_plan.jmx -l results.jtl`.

## P36 — Data retention & privacy

- Политика и полный инвентарь PII: [docs/PRIVACY_PII_INVENTORY.md](docs/PRIVACY_PII_INVENTORY.md).
- Админ API (JWT + `admin.adminUserIds`):
  - `POST /api/admin/privacy/erase` — dry-run (`{"dryRun":true}`) перед реальным удалением; ответ — `ErasureReport` с удалёнными/анонимизированными таблицами.
  - `POST /api/admin/privacy/retention/run` — форсирует немедленную очистку по TTL поверх фонового планировщика.
- CLI: `tools/privacy/erase_user.sh <user_id> [--dry]`, `tools/privacy/run_retention.sh` (требуют `JWT` и опционально `BASE`).
- Автосервис запускает retention-раз в 24 часа; ручные вызовы не нарушают расписание и журналируются без PII (`privacy_erasure_log`).
