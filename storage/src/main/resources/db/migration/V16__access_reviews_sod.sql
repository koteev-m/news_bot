-- Periodic access reviews (attestations)
CREATE TABLE access_reviews (
  review_id      BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  reviewer_id    BIGINT NOT NULL,
  due_at         TIMESTAMPTZ NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  status         TEXT NOT NULL CHECK (status IN ('OPEN','APPROVED','REVOKED','EXPIRED')) DEFAULT 'OPEN'
);

CREATE TABLE access_review_items (
  item_id        BIGSERIAL PRIMARY KEY,
  review_id      BIGINT NOT NULL REFERENCES access_reviews(review_id) ON DELETE CASCADE,
  user_id        BIGINT NOT NULL,
  role           TEXT NOT NULL,
  decision       TEXT CHECK (decision IN ('KEEP','REVOKE','PENDING')) DEFAULT 'PENDING',
  decided_at     TIMESTAMPTZ
);

-- Separation of Duties (SoD) constraints
CREATE TABLE sod_policies (
  policy_id      BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  name           TEXT NOT NULL,
  roles_conflict TEXT[] NOT NULL,
  enabled        BOOLEAN NOT NULL DEFAULT true,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Privileged Access Mgmt (PAM) — break-glass sessions
CREATE TABLE pam_sessions (
  session_id     BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  user_id        BIGINT NOT NULL,
  reason         TEXT NOT NULL,
  granted_roles  TEXT[] NOT NULL,
  started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at     TIMESTAMPTZ NOT NULL,
  approved_by    BIGINT,
  status         TEXT NOT NULL CHECK (status IN ('REQUESTED','APPROVED','REVOKED','EXPIRED')) DEFAULT 'REQUESTED'
);

-- расписание ревизий (ежеквартально/ежемесячно)
CREATE TABLE review_schedules (
  tenant_id      BIGINT PRIMARY KEY,
  freq           TEXT NOT NULL CHECK (freq IN ('MONTHLY','QUARTERLY')),
  next_due_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_access_review_items_review ON access_review_items(review_id);
CREATE INDEX idx_pam_sessions_tenant ON pam_sessions(tenant_id, status);
