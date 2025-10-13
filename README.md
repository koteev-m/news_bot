## P82 — Enterprise SSO (OIDC/SAML) + SCIM Provisioning

- DB migrations: `VXX__sso_scim.sql`
- Core: `sso/IdpModels.kt`, `sso/GroupRoleMapper.kt`
- Repo: `repo/IdpRepository.kt`
- App: `AppSsoScim.kt`, routes `oidcRoutes`, `scimRoutes`
- Config: `application.conf (sso, scim)`
- CI: `.github/workflows/sso-scim-smoke.yml`

Пример:
```bash
curl -I "$BASE/sso/oidc/login?issuer=https://idp.example.com&redirect_uri=$BASE/sso/oidc/callback"
curl "$BASE/scim/v2/ServiceProviderConfig"
```



⸻
