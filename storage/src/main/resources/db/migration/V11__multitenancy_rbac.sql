-- Организации / арендаторы / проекты / роли / API-ключи / квоты

CREATE TABLE orgs (
  org_id       BIGSERIAL PRIMARY KEY,
  name         TEXT NOT NULL UNIQUE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tenants (
  tenant_id    BIGSERIAL PRIMARY KEY,
  org_id       BIGINT NOT NULL REFERENCES orgs(org_id) ON DELETE CASCADE,
  slug         TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projects (
  project_id   BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
  key          TEXT NOT NULL,
  name         TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, key)
);

CREATE TABLE members (
  tenant_id    BIGINT NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
  user_id      BIGINT NOT NULL,
  role         TEXT NOT NULL CHECK (role IN ('OWNER','ADMIN','DEVELOPER','VIEWER')),
  added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id, user_id)
);

CREATE TABLE api_keys (
  key_id       BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  token_hash   TEXT NOT NULL,
  scopes       TEXT[] NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at   TIMESTAMPTZ
);

CREATE TABLE quotas (
  tenant_id    BIGINT PRIMARY KEY REFERENCES tenants(tenant_id) ON DELETE CASCADE,
  max_portfolios   INTEGER NOT NULL DEFAULT 10,
  max_alerts       INTEGER NOT NULL DEFAULT 100,
  rps_soft         INTEGER NOT NULL DEFAULT 20,
  rps_hard         INTEGER NOT NULL DEFAULT 50,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_tenant ON projects(tenant_id);
CREATE INDEX idx_members_tenant ON members(tenant_id);
