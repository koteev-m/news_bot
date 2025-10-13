-- Immutable Audit Ledger with hash chain & daily checkpoints

CREATE TABLE audit_ledger (
  seq_id       BIGSERIAL PRIMARY KEY,              -- append-only
  ts           TIMESTAMPTZ NOT NULL DEFAULT now(), -- event time (server UTC)
  actor_type   TEXT NOT NULL,                      -- user|service|system
  actor_id     TEXT,                               -- user_id / client_id
  tenant_id    BIGINT,                             -- nullable for global events
  action       TEXT NOT NULL,                      -- e.g., "login.success", "invoice.issued"
  resource     TEXT NOT NULL,                      -- e.g., "/api/billing/invoice/issue"
  meta_json    JSONB NOT NULL DEFAULT '{}'::jsonb, -- non-PII metadata
  prev_hash    TEXT NOT NULL,                      -- hex sha256 of prev record (or GENESIS)
  hash         TEXT NOT NULL                       -- hex sha256 of (seq_id|ts|actor|...|prev_hash)
);

CREATE UNIQUE INDEX uq_audit_hash ON audit_ledger(hash);
CREATE INDEX idx_audit_ts ON audit_ledger(ts DESC);
CREATE INDEX idx_audit_tenant ON audit_ledger(tenant_id, ts DESC);

-- Daily checkpoints — подпись среза
CREATE TABLE audit_checkpoints (
  day          DATE PRIMARY KEY,
  last_seq_id  BIGINT NOT NULL,
  root_hash    TEXT NOT NULL,        -- merkle-like (или просто hash последней записи)
  signature    TEXT NOT NULL,        -- detached signature (например, cosign/gpg)
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
