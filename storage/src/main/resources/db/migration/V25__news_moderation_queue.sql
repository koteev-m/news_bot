CREATE TABLE moderation_queue (
  moderation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  cluster_id UUID NOT NULL,
  cluster_key TEXT NOT NULL,
  suggested_mode TEXT NOT NULL,
  score NUMERIC(10, 4) NOT NULL DEFAULT 0,
  confidence NUMERIC(6, 4) NOT NULL DEFAULT 0,
  links TEXT NOT NULL,
  source_domain TEXT NOT NULL,
  entity_hashes TEXT NOT NULL,
  primary_entity_hash TEXT NULL,
  title TEXT NOT NULL,
  summary TEXT NULL,
  topics TEXT NOT NULL DEFAULT '{}',
  deep_link TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  action_id TEXT NULL,
  action_at TIMESTAMPTZ NULL,
  edit_requested_at TIMESTAMPTZ NULL,
  admin_chat_id BIGINT NULL,
  admin_thread_id BIGINT NULL,
  admin_message_id BIGINT NULL,
  published_channel_id BIGINT NULL,
  published_message_id BIGINT NULL,
  published_at TIMESTAMPTZ NULL,
  edited_text TEXT NULL
);

CREATE UNIQUE INDEX uk_moderation_queue_cluster ON moderation_queue(cluster_key);
CREATE UNIQUE INDEX uk_moderation_queue_action ON moderation_queue(action_id);
CREATE INDEX idx_moderation_queue_status ON moderation_queue(status);
CREATE INDEX idx_moderation_queue_created_at ON moderation_queue(created_at DESC);

CREATE TABLE mute_source (
  source_domain TEXT PRIMARY KEY,
  muted_until TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE mute_entity (
  entity_hash TEXT PRIMARY KEY,
  muted_until TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
