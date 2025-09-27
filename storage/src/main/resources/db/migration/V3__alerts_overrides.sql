-- Хранилище персональных оверрайдов настроек алертов (по tg_user_id)
CREATE TABLE user_alert_overrides (
  user_id     BIGINT PRIMARY KEY,
  payload     JSONB NOT NULL,                  -- частичный объект (patch), валидируется приложением
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_alert_overrides_updated ON user_alert_overrides (updated_at DESC);
