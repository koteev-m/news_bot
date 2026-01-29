CREATE TABLE publish_jobs (
  job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  cluster_id UUID NOT NULL,
  cluster_key TEXT NOT NULL,
  target TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  scheduled_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ NULL,
  processing_owner TEXT NULL,
  title TEXT NOT NULL,
  summary TEXT NULL,
  source_domain TEXT NOT NULL,
  topics TEXT NOT NULL DEFAULT '{}',
  deep_link TEXT NOT NULL
);

CREATE UNIQUE INDEX uk_publish_jobs_cluster_target ON publish_jobs(cluster_id, target);
CREATE INDEX idx_publish_jobs_status_schedule ON publish_jobs(status, scheduled_at);
CREATE INDEX idx_publish_jobs_target_status ON publish_jobs(target, status);
