-- Кампании / путешествия (journeys) / сегменты / рассылки / капы / согласия

CREATE TABLE growth_campaigns (
  campaign_id      BIGSERIAL PRIMARY KEY,
  name             TEXT NOT NULL,
  status           TEXT NOT NULL CHECK (status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
  channel          TEXT NOT NULL CHECK (channel IN ('telegram','email','webhook')),
  locale           TEXT NOT NULL DEFAULT 'en',
  template_id      BIGINT,
  frequency_cap_day INTEGER NOT NULL DEFAULT 1,
  quiet_hours_from  TIME WITHOUT TIME ZONE DEFAULT '22:00',
  quiet_hours_to    TIME WITHOUT TIME ZONE DEFAULT '08:00',
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE growth_templates (
  template_id      BIGSERIAL PRIMARY KEY,
  name             TEXT NOT NULL UNIQUE,
  channel          TEXT NOT NULL CHECK (channel IN ('telegram','email','webhook')),
  locale           TEXT NOT NULL DEFAULT 'en',
  subject          TEXT,
  body_md          TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE growth_segments (
  segment_id       BIGSERIAL PRIMARY KEY,
  name             TEXT NOT NULL UNIQUE,
  definition_sql   TEXT NOT NULL, -- SQL (ClickHouse/OLAP) или internal DSL
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE growth_journeys (
  journey_id       BIGSERIAL PRIMARY KEY,
  name             TEXT NOT NULL UNIQUE,
  status           TEXT NOT NULL CHECK (status IN ('DRAFT','ACTIVE','PAUSED')),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE growth_journey_nodes (
  node_id          BIGSERIAL PRIMARY KEY,
  journey_id       BIGINT NOT NULL REFERENCES growth_journeys(journey_id) ON DELETE CASCADE,
  kind             TEXT NOT NULL CHECK (kind IN ('ENTRY','WAIT','CHECK','SEND','EXIT')),
  config_json      JSONB NOT NULL,
  position         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE growth_journey_edges (
  edge_id          BIGSERIAL PRIMARY KEY,
  journey_id       BIGINT NOT NULL REFERENCES growth_journeys(journey_id) ON DELETE CASCADE,
  from_node_id     BIGINT NOT NULL REFERENCES growth_journey_nodes(node_id) ON DELETE CASCADE,
  to_node_id       BIGINT NOT NULL REFERENCES growth_journey_nodes(node_id) ON DELETE CASCADE,
  condition_json   JSONB
);

CREATE TABLE growth_deliveries (
  delivery_id      BIGSERIAL PRIMARY KEY,
  campaign_id      BIGINT REFERENCES growth_campaigns(campaign_id) ON DELETE SET NULL,
  journey_id       BIGINT REFERENCES growth_journeys(journey_id) ON DELETE SET NULL,
  user_id          BIGINT NOT NULL,
  tenant_id        BIGINT NOT NULL,
  channel          TEXT NOT NULL,
  status           TEXT NOT NULL CHECK (status IN ('QUEUED','SENT','FAILED','SKIPPED')),
  reason           TEXT,
  locale           TEXT,
  payload_json     JSONB,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at          TIMESTAMPTZ
);

CREATE TABLE growth_suppressions (
  user_id          BIGINT NOT NULL,
  tenant_id        BIGINT NOT NULL,
  channel          TEXT NOT NULL,
  reason           TEXT,
  opted_out_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, tenant_id, channel)
);

CREATE TABLE growth_rate_budget (
  tenant_id        BIGINT PRIMARY KEY,
  per_minute       INTEGER NOT NULL DEFAULT 120,
  per_day          INTEGER NOT NULL DEFAULT 1500,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deliveries_user ON growth_deliveries(user_id, tenant_id);
CREATE INDEX idx_deliveries_created ON growth_deliveries(created_at);
