# P82 — Enterprise SSO (OIDC/SAML) + SCIM

## Что включено
- Модель IdP (OIDC/SAML), таблицы, маппинги групп → ролей.
- OIDC маршруты (login/callback, mock-flow).
- SCIM v2 mock (ServiceProviderConfig, Users CRUD).
- SSO sessions для аудита.
- CI smoke для SSO/SCIM.

## Как подключать
- OIDC Discovery: `/.well-known/openid-configuration` → получить `authorization_endpoint` / `token_endpoint` / `jwks_uri`.
- Валидируйте `id_token` (issuer, audience, exp), извлекайте `sub/email/groups`.
- Маппинг групп → платформенных ролей (`idp_group_mappings`), JIT-провижининг пользователя/члена тенанта.
- SCIM: подключите IdP (Okta/Azure AD) к `POST /scim/v2/Users` и обновляйте `scim_users`.

## Безопасность
- Не храните секреты клиента в репозитории.
- Ставьте mTLS/ingress policies для SSO/SCIM.
- Добавьте rate-limit на SCIM маршруты.
