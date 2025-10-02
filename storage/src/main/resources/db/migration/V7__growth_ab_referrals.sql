-- Experiments config (admin-managed)
CREATE TABLE IF NOT EXISTS experiments (
  key        TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  enabled    BOOLEAN NOT NULL DEFAULT true,
  traffic    JSONB  NOT NULL,
  scope      TEXT   NOT NULL DEFAULT 'GLOBAL',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Persisted assignments (deterministic, but cache/store for audit)
CREATE TABLE IF NOT EXISTS experiment_assignments (
  user_id    BIGINT NOT NULL,
  key        TEXT   NOT NULL,
  variant    TEXT   NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, key),
  FOREIGN KEY (key) REFERENCES experiments(key) ON DELETE CASCADE
);

-- Referral program: codes and visits
CREATE TABLE IF NOT EXISTS referrals (
  ref_code   TEXT PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS referral_visits (
  id         BIGSERIAL PRIMARY KEY,
  ref_code   TEXT NOT NULL REFERENCES referrals(ref_code) ON DELETE CASCADE,
  tg_user_id BIGINT NULL,
  utm_source TEXT NULL,
  utm_medium TEXT NULL,
  utm_campaign TEXT NULL,
  cta_id     TEXT NULL,
  first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (ref_code, tg_user_id)
);

-- Indices for queries
CREATE INDEX IF NOT EXISTS idx_experiments_enabled ON experiments(enabled);
CREATE INDEX IF NOT EXISTS idx_assignments_user ON experiment_assignments(user_id);
CREATE INDEX IF NOT EXISTS idx_ref_visits_code ON referral_visits(ref_code);
CREATE INDEX IF NOT EXISTS idx_ref_visits_tg ON referral_visits(tg_user_id);
