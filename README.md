## P83 — Vault/KMS Envelope Encryption + Rotation

- Core: `crypto/EnvelopeCrypto.kt`
- KMS Adapter: `integrations/kms/VaultKmsAdapter.kt`
- Storage: `repo/SecretStore.kt`
- API: `crypto/CryptoRoutes.kt`
- Rotation helper: `tools/crypto/rotate_key.sh`
- CI: `.github/workflows/secrets-drift.yml`

ENV (пример):
```env
VAULT_TRANSIT_BASE=https://vault.example/v1/transit
VAULT_TOKEN=***
VAULT_KEY_ID=newsbot-app-key
```

## Build & Quality Checks

- Run full verification: `./gradlew clean check --no-daemon --console=plain`
- Lint only: `./gradlew ktlintCheck detekt` (baseline at `config/detekt/baseline.xml`)
- Coverage reports: `./gradlew koverXmlReport` or `./gradlew koverHtmlReport`
- Reports live under `build/reports/kover/xml/report.xml` and `build/reports/kover/html/index.html`
- Detekt reports: `build/reports/detekt/detekt.html` and `build/reports/detekt/detekt.xml`
- ktlint reports: `<module>/build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.txt`

## Growth deeplinks (/r/*)

- `installGrowthRoutes` теперь принимает `MeterRegistry` (например, `prometheusRegistry`).
- В тестах можно использовать `SimpleMeterRegistry` из Micrometer для инициализации роутов.

## P84 — Immutable Audit Ledger & Daily Checkpoints

- Core models & hashing: `core/src/main/kotlin/audit/AuditModels.kt`
- Service orchestration: `core/src/main/kotlin/audit/AuditService.kt`
- Repository & tables: `storage/src/main/kotlin/repo/AuditLedgerRepository.kt`
- HTTP integration: `app/src/main/kotlin/audit/AuditPlugin.kt`, `app/src/main/kotlin/audit/AuditRoutes.kt`, `app/src/main/kotlin/AppAudit.kt`
- Tooling: `tools/audit/ledger_sign_daily.sh`, `tools/audit/ledger_verify.sh`
- Workflows: `.github/workflows/audit-daily.yml`, `.github/workflows/audit-verify.yml`
- Runbook & design: `docs/AUDIT_LEDGER.md`

### Enabling

1. Apply the migration `storage/src/main/resources/db/migration/V15__audit_ledger.sql`.
2. Bootstrap the app with `installAuditLayer()` in your application module (after security/tenant plugins).
3. Provide `DATABASE_URL` (read/write) and configure Cosign or GPG credentials for the signing workflow.
4. Schedule the daily checkpoint workflow and trigger the verification workflow after incidents or releases.
5. Use `/api/audit/ledger/last` and `/api/audit/checkpoint/{day}` for operational status and attestations.

### Notes

- Metadata stored in the ledger must remain non-PII.
- Cosign keyless signing requires `id-token: write` permissions in GitHub Actions and `COSIGN_EXPERIMENTAL=1`.
- Verification expects read-only DB credentials and will abort on any hash mismatch.

## P86 — AI-assisted Governance

- RCA сбор: `tools/ai_gov/rca_collect.py`
- Саммари: `tools/ai_gov/llm_summarize.py` (LLM опционально)
- Policy digest: `tools/ai_gov/policy_summarizer.py`
- CI: `.github/workflows/ai-governance-weekly.yml`

Пример (LLM off):
```bash
python3 tools/ai_gov/rca_collect.py
python3 tools/ai_gov/llm_summarize.py
```

## P87 — Multi-Region HA & Global Failover

- Terraform global DNS: `terraform/global/*`
- K8s HA: `k8s/priorityclasses/`, `k8s/overlays/region-*`, `helm/newsbot/values-ha.yaml`
- PostgreSQL logical replication: `deploy/db/pg/*`
- Prometheus federation: `deploy/monitoring/prometheus/federation.yml`
- CI: `.github/workflows/global-health.yml`

Быстрый старт:
```bash
# DNS
terraform -chdir=terraform/global init
terraform -chdir=terraform/global apply \
  -var='zone_id=ZXXXXXXXX' \
  -var='hostname=newsbot.example.com' \
  -var='primary_lb=a123.eu-lb.amazonaws.com' \
  -var='secondary_lb=b456.us-lb.amazonaws.com'

# Проверка
gh workflow run "Global Health & Failover Smoke" -f hostname=newsbot.example.com
```

## P90 — Access Reviews & SoD (Attestations, PAM)

- DB: `V16__access_reviews_sod.sql`
- Core: `access/AccessModels.kt`, `access/AccessServices.kt`
- Repo: `repo/AccessRepoImpl.kt`
- API: `access/AccessRoutes.kt`; wiring: `AppAccess.kt`
- CI: `access-reviews-schedule.yml`
- Docs: `docs/ACCESS_REVIEWS_SOD_PAM.md`

Быстрый старт:
```bash
./gradlew :storage:flywayMigrate
./gradlew :app:compileKotlin
```

## Billing / Stars

- `GET /api/billing/stars/balance` — возвращает баланс звёзд бота через Telegram Bot API `getMyStarBalance` (публичный маршрут, кэш не используется, ставится `Cache-Control: no-store`, `Retry-After` на 429 пробрасывается из Telegram — берётся из заголовка или `parameters.retry_after` в теле ok=false и нормализуется в секунды). Клиент понимает оба формата ответа Bot API: `available_balance`/`pending_balance`/`updated_at` и `amount`/`nanostar_amount`.
- `GET /api/admin/stars/bot-balance` — возвращает баланс звёзд бота через Telegram Bot API `getMyStarBalance` (доступно только администраторам).
- `GET /api/billing/entitlements` — возвращает entitlement на основе активной подписки.
- Схема подписок на звёзды — миграции `V18__star_subscriptions.sql` (таблица `star_subscriptions` c индексом `ux_star_subscriptions_user_active`) и `V19__star_subscriptions_checks.sql` (CHECK: `status ∈ {ACTIVE, CANCELED, EXPIRED, TRIAL}`, `plan` непустой).
- StarsClient отправляет заголовки `User-Agent: stars-client` и `Accept: application/json` во все запросы к Telegram Bot API.

Поведение админского `/api/admin/stars/bot-balance`:

- Минимальный TTL кэша — 5 секунд (значение из `billing.stars.balanceTtlSeconds` принудительно приводится к минимуму 5 сек; дефолт 20 сек).
- Максимальная «черствость» кэша задаётся `billing.stars.maxStaleSeconds` (дефолт 300 сек). Если кэш старше — при ошибках Telegram возвращаем соответствующую 5xx/429, а не stale.
- Пер-админ rate limit (`billing.stars.adminRateLimitPerMinute`, дефолт 30 запросов в минуту) — при превышении возвращает `429 Too Many Requests` и заголовок `Retry-After`.
- Коды ответов и условия:
  - `200 OK` — успешный ответ; заголовки `Cache-Control: no-store`, `X-Stars-Cache: hit|miss|stale`, `X-Stars-Cache-Age: <seconds>`.
  - `401 Unauthorized` — нет аутентификации.
  - `403 Forbidden` — не из списка админов.
  - `429 Too Many Requests` — rate limit Telegram или локальный лимитер; `Retry-After` только если пришёл от Telegram или локального лимитера.
  - `502 Bad Gateway` — ошибки Telegram 4xx или decode_error.
  - `503 Service Unavailable` — Telegram не сконфигурирован или отвечает 5xx.
  - `500 Internal Server Error` — прочие неожиданные исключения.
- Заголовки: `Cache-Control: no-store` всегда; `X-Stars-Cache`/`X-Stars-Cache-Age` только при `200 OK`; `Retry-After` только при `429` и только если был в ответе Telegram или в локальном rate limit. `X-Stars-Cache-Age` отражает возраст записи кэша, а не «свежесть» данных Telegram.
- Поле `updatedAtEpochSeconds` приходит из Telegram и может не меняться, пока кэш стареет; используйте его как «метку источника», а не как возраст кэшированной записи.

Конфигурация для Stars находится в `application.conf` (`billing.stars.*`) и использует переменную окружения `TELEGRAM_BOT_TOKEN`. Полезные параметры:

- `billing.stars.balanceTtlSeconds` — TTL кэша баланса бота (минимум 5 сек, дефолт 20 сек).
- `billing.stars.maxStaleSeconds` — максимальная допустимая черствость при fallback на кэш (дефолт 300 сек). При превышении возвращаем 5xx/429 и считаем bounded_stale метрику.
- `billing.stars.adminRateLimitPerMinute` — лимит обращений на админский маршрут на одного администратора (дефолт 30/min).
- `billing.stars.http.connectTimeoutMs` / `readTimeoutMs` — таймауты StarsClient.
- `billing.stars.http.retryMax` / `retryBaseDelayMs` — параметры ретраев StarsClient.
- Публичный `GET /api/billing/stars/balance` использует тот же `TELEGRAM_BOT_TOKEN`; при его отсутствии возвращает `503 Service Unavailable`. Ошибки Telegram мапятся в `429 Too Many Requests` (с `Retry-After`, если Telegram прислал число или HTTP-дата), `502 Bad Gateway`, `503 Service Unavailable`, остальные — в `500 Internal Server Error`. Кэш не используется.

Метрики Stars:

| Metric | Type | Labels | Description |
| --- | --- | --- | --- |
| `stars_bot_balance_fetch_seconds` | Timer | — | Латентность запросов к Telegram Bot API `getMyStarBalance` (баланс бота). |
| `stars_balance_fetch_seconds` | Timer | — | Алиас для обратной совместимости на тот же вызов (баланс бота). |
| `stars_admin_bot_balance_request_seconds` | Timer | — | Латентность обработки админского эндпоинта `GET /api/admin/stars/bot-balance` (включая ошибки авторизации/лимита/Telegram). |
| `stars_public_bot_balance_request_seconds` | Timer | — | Латентность обработки публичного эндпоинта `GET /api/billing/stars/balance`. |
| `stars_bot_balance_fetch_total` | Counter | `outcome` | Исход запросов к Telegram за балансом бота (`success`, `rate_limited`, `server`, `bad_request`, `decode_error`, `other`, `stale_returned`). Счётчик отражает обращения как из публичного, так и из админского маршрутов. |
| `stars_bot_balance_bounded_stale_total` | Counter | `reason` | Отсечки по слишком старому кэшу при fallback (`rate_limited`, `server`, `bad_request`, `decode_error`, `other`). |
| `stars_admin_bot_balance_requests_total` | Counter | `result` | Исходы админского эндпоинта (`ok`, `unauthorized`, `forbidden`, `unconfigured`, `local_rate_limited`, `tg_rate_limited`, `server`, `bad_request`, `decode_error`, `other`). |
| `stars_public_bot_balance_requests_total` | Counter | `result` | Исходы публичного эндпоинта (`ok`, `unconfigured`, `tg_rate_limited`, `server`, `bad_request`, `decode_error`, `other`). |
| `stars_bot_balance_cache_total` | Counter | `state` | Состояние обращения к кэшу (`hit`, `miss`, `stale`). |
| `stars_bot_balance_cache_age_seconds` | Gauge | — | Возраст текущего значения кэша в секундах. |
| `stars_bot_balance_cache_ttl_seconds` | Gauge | — | Текущий TTL (секунды), с которым инициализирован сервис. |
| `stars_bot_balance_rl_window_remaining_seconds` | Gauge | — | Остаток окна rate limit Telegram (секунды), 0 если окна нет. |
| `stars_subscriptions_paid_active_gauge` | Gauge | — | Количество платных активных подписок (исключая trial). |
| `stars_subscriptions_renew_attempt_total` | Counter | `result` | Исходы попыток продления подписок (`success`, `failed`). |
| `stars_subscriptions_transactions_total` | Counter | `result` | Исходы активаций/отмен (`activated`, `canceled`, `noop`). |

Быстрые SLO/алерты для бота:

- Ошибки Telegram: доля `outcome ∈ {server,bad_request,decode_error,other}` выше базовой нормы за 10 минут.
- Всплески `rate_limited` и `stale_returned` (дольше N минут подряд).
- Возраст кэша (`stars_bot_balance_cache_age_seconds`) выше `maxStaleSeconds`.
- Для диагностики добавлен заголовок `X-Stars-Cache-Age` в 200-ответах.
