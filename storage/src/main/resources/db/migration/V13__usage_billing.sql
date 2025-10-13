-- Usage metering / rate cards / invoices

CREATE TABLE usage_events (
  event_id      BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  project_id    BIGINT,
  user_id       BIGINT,
  metric        TEXT NOT NULL,               -- e.g., api.calls, alerts.sent, storage.mb
  quantity      NUMERIC(20,6) NOT NULL,      -- base units
  occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  dedup_key     TEXT UNIQUE                  -- optional idempotency
);

CREATE INDEX idx_usage_events_tenant_time ON usage_events(tenant_id, occurred_at DESC);
CREATE INDEX idx_usage_events_metric ON usage_events(metric);

CREATE TABLE rate_cards (
  rate_id       BIGSERIAL PRIMARY KEY,
  name          TEXT NOT NULL UNIQUE,
  currency      TEXT NOT NULL DEFAULT 'USD',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rate_items (
  item_id       BIGSERIAL PRIMARY KEY,
  rate_id       BIGINT NOT NULL REFERENCES rate_cards(rate_id) ON DELETE CASCADE,
  metric        TEXT NOT NULL,
  unit          TEXT NOT NULL,                -- 'call', 'mb', 'message'
  price_per_unit NUMERIC(20,6) NOT NULL,      -- price per base unit
  tier_from     NUMERIC(20,6) NOT NULL DEFAULT 0,  -- inclusive
  tier_to       NUMERIC(20,6),                    -- exclusive (NULL = infinity)
  UNIQUE(rate_id, metric, tier_from)
);

CREATE TABLE tenant_pricing (
  tenant_id     BIGINT PRIMARY KEY,
  rate_id       BIGINT NOT NULL REFERENCES rate_cards(rate_id) ON DELETE RESTRICT,
  effective_from TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invoice_runs (
  run_id        BIGSERIAL PRIMARY KEY,
  period_from   TIMESTAMPTZ NOT NULL,
  period_to     TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
  invoice_id    BIGSERIAL PRIMARY KEY,
  run_id        BIGINT NOT NULL REFERENCES invoice_runs(run_id) ON DELETE CASCADE,
  tenant_id     BIGINT NOT NULL,
  currency      TEXT NOT NULL,
  subtotal      NUMERIC(20,6) NOT NULL DEFAULT 0,
  tax           NUMERIC(20,6) NOT NULL DEFAULT 0,
  total         NUMERIC(20,6) NOT NULL DEFAULT 0,
  status        TEXT NOT NULL CHECK (status IN ('DRAFT','ISSUED','PAID','VOID')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  paid_at       TIMESTAMPTZ
);

CREATE TABLE invoice_lines (
  line_id       BIGSERIAL PRIMARY KEY,
  invoice_id    BIGINT NOT NULL REFERENCES invoices(invoice_id) ON DELETE CASCADE,
  metric        TEXT NOT NULL,
  quantity      NUMERIC(20,6) NOT NULL,
  unit          TEXT NOT NULL,
  unit_price    NUMERIC(20,6) NOT NULL,
  amount       NUMERIC(20,6) NOT NULL
);

-- Индексы
CREATE INDEX idx_invoices_tenant ON invoices(tenant_id, created_at DESC);
