-- Product analytics events storage
CREATE TABLE events (
  event_id   BIGSERIAL PRIMARY KEY,
  ts         TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_id    BIGINT NULL,                -- Telegram user id if known
  type       TEXT    NOT NULL,           -- e.g. 'post_published','cta_click','bot_start','miniapp_auth','stars_payment_succeeded','stars_payment_duplicate','portfolio_import','alerts_push_sent','alerts_push_blocked'
  source     TEXT    NULL,               -- e.g. 'news_publisher','webhook','api'
  session_id TEXT    NULL,               -- optional correlation (miniapp session)
  props      JSONB   NOT NULL DEFAULT '{}'::jsonb
);

-- Indexes for time- and type-based queries
CREATE INDEX idx_events_ts_desc   ON events (ts DESC);
CREATE INDEX idx_events_type_ts   ON events (type, ts DESC);
CREATE INDEX idx_events_user_ts   ON events (user_id, ts DESC);
CREATE INDEX idx_events_props_gin ON events USING GIN (props);
