-- Глобальные оверрайды фич (одна запись с ключом 'global')
CREATE TABLE feature_overrides (
  key         TEXT PRIMARY KEY,   -- 'global'
  payload     JSONB NOT NULL,     -- частичный объект (patch)
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Идемпотентный seed пустого патча
INSERT INTO feature_overrides(key, payload) VALUES ('global', '{}'::jsonb)
ON CONFLICT (key) DO NOTHING;
