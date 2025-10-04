-- Ledger: неизменяемые записи о начислениях (stars → подписки)
CREATE TABLE billing_ledger (
  ledger_id   BIGSERIAL PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  tier        TEXT   NOT NULL,
  event       TEXT   NOT NULL CHECK (event IN ('APPLY','DUPLICATE','REVERSAL')),
  provider_payment_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_billing_ledger_pid_event ON billing_ledger(provider_payment_id, event);

-- Таблица результатов ежедневной сверки
CREATE TABLE billing_recon_runs (
  run_id     BIGSERIAL PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  status     TEXT NOT NULL CHECK (status IN ('OK','WARN','FAIL')),
  notes      TEXT
);

-- Детализация расхождений (только коды/идентификаторы)
CREATE TABLE billing_recon_mismatches (
  run_id     BIGINT NOT NULL REFERENCES billing_recon_runs(run_id) ON DELETE CASCADE,
  kind       TEXT   NOT NULL,     -- 'DUPLICATE','MISSING_LEDGER','MISSING_SUB'
  user_id    BIGINT,
  provider_payment_id TEXT,
  tier       TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_billing_recon_mismatches_run ON billing_recon_mismatches(run_id);
