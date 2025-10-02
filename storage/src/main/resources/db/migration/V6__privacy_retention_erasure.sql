-- Очередь запросов на удаление и журнал (без PII)
CREATE TABLE IF NOT EXISTS privacy_erasure_queue (
  user_id      BIGINT PRIMARY KEY,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status       TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DONE','DRYRUN','ERROR')),
  last_error   TEXT,
  processed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS privacy_erasure_log (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  action     TEXT NOT NULL CHECK (action IN ('DELETE','ANONYMIZE','SKIP')),
  table_name TEXT NOT NULL,
  affected   BIGINT NOT NULL,
  ts         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Индексы по времени для TTL-очистки
CREATE INDEX IF NOT EXISTS idx_events_ts_desc        ON events (ts DESC);
CREATE INDEX IF NOT EXISTS idx_alerts_events_ts_desc ON alerts_events (ts DESC);
CREATE INDEX IF NOT EXISTS idx_bot_starts_ts_desc    ON bot_starts (started_at DESC);
