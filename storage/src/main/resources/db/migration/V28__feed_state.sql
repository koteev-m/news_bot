CREATE TABLE IF NOT EXISTS feed_state (
    source_id TEXT PRIMARY KEY,
    etag TEXT NULL,
    last_modified TEXT NULL,
    last_fetched_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ NULL,
    failure_count INTEGER NOT NULL DEFAULT 0,
    cooldown_until TIMESTAMPTZ NULL
);
