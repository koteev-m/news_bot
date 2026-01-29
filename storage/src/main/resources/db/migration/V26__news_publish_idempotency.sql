ALTER TABLE post_stats
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE post_stats
    ADD COLUMN IF NOT EXISTS duplicate_count INT NOT NULL DEFAULT 0;

ALTER TABLE post_stats
    ADD COLUMN IF NOT EXISTS content_hash TEXT;

WITH ranked_duplicates AS (
    SELECT
        post_id,
        ROW_NUMBER() OVER (
            PARTITION BY channel_id, cluster_id
            ORDER BY posted_at DESC, post_id DESC
        ) AS row_num
    FROM post_stats
    WHERE cluster_id IS NOT NULL
)
UPDATE post_stats
SET cluster_id = NULL
FROM ranked_duplicates
WHERE post_stats.post_id = ranked_duplicates.post_id
  AND ranked_duplicates.row_num > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_post_channel_cluster
    ON post_stats(channel_id, cluster_id);

CREATE TABLE IF NOT EXISTS published_posts (
    post_key TEXT PRIMARY KEY,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
