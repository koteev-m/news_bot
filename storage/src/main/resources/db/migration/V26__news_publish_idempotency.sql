ALTER TABLE post_stats
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE post_stats
    ADD COLUMN IF NOT EXISTS duplicate_count INT NOT NULL DEFAULT 0;

ALTER TABLE post_stats
    ADD COLUMN IF NOT EXISTS content_hash TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_post_channel_cluster
    ON post_stats(channel_id, cluster_id);

CREATE TABLE IF NOT EXISTS published_posts (
    post_key TEXT PRIMARY KEY,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
