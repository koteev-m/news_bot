-- FAQ статьи (без PII)
CREATE TABLE support_faq (
  id          BIGSERIAL PRIMARY KEY,
  locale      TEXT NOT NULL,                -- 'ru'|'en'
  slug        TEXT NOT NULL,
  title       TEXT NOT NULL,
  body_md     TEXT NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(locale, slug)
);

-- Тикеты (минимальные поля; без PII по умолчанию)
CREATE TABLE support_tickets (
  ticket_id   BIGSERIAL PRIMARY KEY,
  ts          TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_id     BIGINT NULL,
  category    TEXT NOT NULL,                -- 'billing'|'import'|'bug'|'idea'|...
  locale      TEXT NOT NULL,
  subject     TEXT NOT NULL,
  message     TEXT NOT NULL,
  status      TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACK','RESOLVED','REJECTED')),
  app_version TEXT NULL,
  device_info TEXT NULL
);

CREATE INDEX idx_support_tickets_ts ON support_tickets(ts DESC);
CREATE INDEX idx_support_tickets_status ON support_tickets(status);
