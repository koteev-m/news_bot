# P35 — Security hardening / Укрепление безопасности

## Security headers / Заголовки безопасности
- **Ktor:** `SecurityHeaders` plugin adds CSP (`default-src 'none'`), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and a strict `Permissions-Policy`. HSTS (`max-age=31536000; includeSubDomains; preload`) is enabled only when `APP_PROFILE=prod`, avoiding noise in local setups. `/metrics` and `/health/db` stay untouched to keep Prometheus and liveness probes simple.
- **Nginx:** mirrors the same headers at the edge and enforces HTTPS. Using `add_header ... always;` guarantees headers on 200/4xx/5xx responses. Keep TLS certificates in `deploy/compose/nginx/certs/`.

## Global rate limits / Глобальные лимиты
- **Nginx (per IP):** `limit_req_zone $binary_remote_addr zone=perip:10m rate=120r/m;` protects from volumetric spikes before traffic hits the app. `/metrics` and `/healthz` are excluded.
- **Ktor (per subject):** in-memory token-bucket keyed by user id (JWT subject) or client IP (`X-Forwarded-For` → `remoteHost`). Defaults: 60 req/min, burst 20. Responses over quota return `429` + `Retry-After` header and a compact JSON `{ "error": "rate_limited" }`. Adjust numbers via `security.rateLimit` in `application.conf` or environment overrides.
- **Testing:** automated smoke in `GlobalRateLimitTest` ensures 2 allowed requests, 1 throttled, refill after ~1.5s. Include the plugin in integration tests for regression coverage.

## JWT dual-key rotation / Двойная ротация JWT-ключей
- `JwtKeys` loads `security.jwtSecretPrimary` and optional `security.jwtSecretSecondary`. Signing uses the primary key; verification tries secondary when present, enabling no-downtime rotation.
- Update secrets with `tools/security/rotate_jwt_secret.sh` to shift the current primary into secondary and apply a new primary. Remove the secondary after 24–48h once all tokens expire (see runbook).
- Keep secrets out of logs and CI output. The scripts never echo secret values.

## Logging hygiene / Гигиена логов
- Avoid storing raw JWTs, IPs, or Telegram personal data in application logs. Use hashed identifiers or counts when aggregation is required. `/metrics` remains free of PII so it can be scraped without extra ACLs.

## CSP & HSTS scope / Область действия CSP и HSTS
- CSP/HSTS are attached to HTML responses from the Ktor plugin and to every response at the edge. Static assets should be served from the same origin or via pre-approved domains defined in future CSP relaxations. Avoid inline scripts/styles unless nonce-based CSP is implemented.

## Permissions-Policy
- `camera=(), microphone=(), geolocation=()` disables high-risk browser capabilities by default. Update cautiously and document business justification before relaxing any directive.

## CORS guidance / Рекомендации по CORS
- Public REST APIs stay locked down: allow only trusted origins (Telegram Mini App, staging dashboards) when enabling CORS. Do not wildcard `Access-Control-Allow-Origin`. Maintain explicit allowlists per environment and mirror them across Nginx and Ktor if exposed directly.
