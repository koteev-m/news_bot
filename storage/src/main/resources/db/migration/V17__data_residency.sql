-- Per-tenant residency policy and data classes

CREATE TABLE data_residency_policies (
  tenant_id     BIGINT PRIMARY KEY,
  region        TEXT NOT NULL CHECK (region IN ('EU','US','AP')),
  data_classes  TEXT[] NOT NULL DEFAULT ARRAY['PII','FIN','LOGS','METRICS'], -- enabled classes
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Evidence snapshots (for compliance bundle)
CREATE TABLE residency_evidence (
  snapshot_id   BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  region        TEXT NOT NULL,
  pi_writes_ok  BOOLEAN NOT NULL,
  ts            TIMESTAMPTZ NOT NULL DEFAULT now()
);
