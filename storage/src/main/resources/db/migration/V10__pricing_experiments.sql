-- Цена/пакеты по умолчанию уже хранятся в billing_plans (tier, price_xtr, is_active)
-- Добавим таблицу оверрайдов по экспериментам
CREATE TABLE pricing_overrides (
  key         TEXT    NOT NULL,           -- experiment key, e.g., 'price_bundle'
  variant     TEXT    NOT NULL,           -- 'A'|'B'|'C'
  tier        TEXT    NOT NULL CHECK (tier IN ('FREE','PRO','PRO_PLUS','VIP')),
  price_xtr   BIGINT  NOT NULL CHECK (price_xtr >= 0),
  stars_package BIGINT NULL,              -- например пакет Stars для автопокупки (опционально)
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (key, variant, tier)
);

-- Тексты paywall по вариантам (сервер отдаёт EN — Mini App локализует)
CREATE TABLE paywall_copy (
  key         TEXT NOT NULL,              -- experiment key 'paywall_copy'
  variant     TEXT NOT NULL,              -- 'A'|'B'
  heading_en  TEXT NOT NULL,
  sub_en      TEXT NOT NULL,
  cta_en      TEXT NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (key, variant)
);
