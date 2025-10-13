# P77 — Multi-tenant & Enterprise RBAC

## Модель
- **Org** → **Tenant** → **Project**
- **Membership**: OWNER / ADMIN / DEVELOPER / VIEWER
- **API keys**: scopes (e.g., `read:portfolio`, `write:alerts`)
- **Quotas**: portfolios, alerts, RPS (soft/hard)

## Извлечение арендатора
- Заголовок `X-Tenant-Slug`, поддомен `{slug}.example.com`, либо claim `X-JWT-Tenant`.

## Встраивание
- Плагин `TenantPlugin` → `TenantContext` в `call.attributes`.
- RBAC `DefaultRbacService` и квоты `QuotaService` в роуты.
- Rate-limit per-tenant `TenantRateLimit` (in-memory, можно заменить на Redis).

## Админ API
- `/api/admin/tenancy/org` — создать организацию
- `/api/admin/tenancy/tenant` — создать арендатора
- `/api/admin/tenancy/member` — добавить участника

## Тестирование
- Unit: `RbacServiceTest`, `TenantResolverTest`.
- E2E: проверьте создание портфелей с `X-Tenant-Slug`, квоты и роли.

> Не логируйте PII/секреты; роли и скоупы валидируйте на стороне сервера.
