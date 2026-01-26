CREATE TABLE IF NOT EXISTS news_pipeline_state (
    key TEXT PRIMARY KEY,
    last_published_epoch_seconds BIGINT NOT NULL DEFAULT 0,
    lease_until_epoch_seconds BIGINT NOT NULL DEFAULT 0,
    lease_owner TEXT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
