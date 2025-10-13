-- Identity Providers (OIDC/SAML)
CREATE TABLE idp_providers (
  idp_id       BIGSERIAL PRIMARY KEY,
  name         TEXT NOT NULL UNIQUE,
  kind         TEXT NOT NULL CHECK (kind IN ('oidc','saml')),
  issuer       TEXT NOT NULL,
  client_id    TEXT,      -- OIDC
  jwks_uri     TEXT,      -- OIDC
  sso_url      TEXT,      -- SAML
  enabled      BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Group → Role mapping per tenant (from external IdP group to platform role)
CREATE TABLE idp_group_mappings (
  tenant_id    BIGINT NOT NULL,
  idp_id       BIGINT NOT NULL REFERENCES idp_providers(idp_id) ON DELETE CASCADE,
  ext_group    TEXT NOT NULL,
  role         TEXT NOT NULL CHECK (role IN ('OWNER','ADMIN','DEVELOPER','VIEWER')),
  PRIMARY KEY (tenant_id, idp_id, ext_group)
);

-- SSO sessions (for audit)
CREATE TABLE sso_sessions (
  session_id   BIGSERIAL PRIMARY KEY,
  user_id      BIGINT,
  tenant_id    BIGINT,
  idp_id       BIGINT,
  subject      TEXT NOT NULL,  -- sub / NameID
  issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ,
  ip           TEXT,
  user_agent   TEXT
);

-- SCIM users shadow (minimal)
CREATE TABLE scim_users (
  scim_id      UUID PRIMARY KEY,
  tenant_id    BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  external_id  TEXT,
  user_name    TEXT NOT NULL,
  email        TEXT,
  active       BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sso_sessions_user ON sso_sessions(user_id, issued_at DESC);
CREATE UNIQUE INDEX uq_scim_users_tenant_user ON scim_users(tenant_id, user_id);
