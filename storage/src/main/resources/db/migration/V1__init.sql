CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  user_id BIGSERIAL PRIMARY KEY,
  tg_user_id BIGINT UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE portfolios (
  portfolio_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  base_currency CHAR(3) NOT NULL CHECK (base_currency ~ '^[A-Z]{3}$'),
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, name)
);

CREATE TABLE instruments (
  instrument_id BIGSERIAL PRIMARY KEY,
  class TEXT NOT NULL CHECK (class IN ('EQUITY','BOND','INDEX','FX','CRYPTO','OTHER')),
  exchange TEXT NOT NULL CHECK (exchange IN ('MOEX','FX','CRYPTO','OTHER')),
  board TEXT,
  symbol TEXT NOT NULL,
  isin TEXT UNIQUE,
  cg_id TEXT,
  currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (exchange, board, symbol)
);
CREATE INDEX idx_instruments_symbol ON instruments(symbol);

CREATE TABLE instrument_aliases (
  alias_id BIGSERIAL PRIMARY KEY,
  instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id) ON DELETE CASCADE,
  alias TEXT NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('ISS','COINGECKO','MANUAL','NEWS')),
  UNIQUE (alias, source)
);
CREATE INDEX idx_aliases_instr ON instrument_aliases(instrument_id);

CREATE TABLE trades (
  trade_id BIGSERIAL PRIMARY KEY,
  portfolio_id UUID NOT NULL REFERENCES portfolios(portfolio_id) ON DELETE CASCADE,
  instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id) ON DELETE RESTRICT,
  datetime TIMESTAMPTZ NOT NULL,
  side TEXT NOT NULL CHECK (side IN ('BUY','SELL')),
  quantity NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
  price NUMERIC(20,8) NOT NULL CHECK (price > 0),
  price_currency CHAR(3) NOT NULL CHECK (price_currency ~ '^[A-Z]{3}$'),
  fee NUMERIC(20,8) NOT NULL DEFAULT 0,
  fee_currency CHAR(3) NOT NULL DEFAULT 'RUB',
  tax NUMERIC(20,8) NOT NULL DEFAULT 0,
  tax_currency CHAR(3),
  broker TEXT,
  note TEXT,
  ext_id TEXT UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (portfolio_id, instrument_id, datetime, side, quantity, price)
);
CREATE INDEX idx_trades_portfolio_time ON trades(portfolio_id, datetime);

CREATE TABLE positions (
  portfolio_id UUID NOT NULL REFERENCES portfolios(portfolio_id) ON DELETE CASCADE,
  instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id) ON DELETE RESTRICT,
  qty NUMERIC(20,8) NOT NULL DEFAULT 0,
  avg_price NUMERIC(20,8),
  avg_price_ccy CHAR(3) CHECK (avg_price_ccy ~ '^[A-Z]{3}$'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (portfolio_id, instrument_id)
);

CREATE TABLE prices (
  instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id) ON DELETE CASCADE,
  ts TIMESTAMPTZ NOT NULL,
  price NUMERIC(20,8) NOT NULL,
  ccy CHAR(3) NOT NULL CHECK (ccy ~ '^[A-Z]{3}$'),
  source TEXT NOT NULL,
  PRIMARY KEY (instrument_id, ts)
);
CREATE INDEX idx_prices_ts ON prices(ts DESC);

CREATE TABLE fx_rates (
  ccy CHAR(3) NOT NULL CHECK (ccy ~ '^[A-Z]{3}$'),
  ts TIMESTAMPTZ NOT NULL,
  rate_rub NUMERIC(20,8) NOT NULL CHECK (rate_rub > 0),
  source TEXT NOT NULL,
  PRIMARY KEY (ccy, ts)
);

CREATE TABLE valuations_daily (
  portfolio_id UUID NOT NULL REFERENCES portfolios(portfolio_id) ON DELETE CASCADE,
  date DATE NOT NULL,
  value_rub NUMERIC(24,8) NOT NULL,
  pnl_day NUMERIC(24,8) NOT NULL DEFAULT 0,
  pnl_total NUMERIC(24,8) NOT NULL DEFAULT 0,
  drawdown NUMERIC(24,8) NOT NULL DEFAULT 0,
  PRIMARY KEY (portfolio_id, date)
);

CREATE TABLE alerts_rules (
  rule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
  portfolio_id UUID REFERENCES portfolios(portfolio_id) ON DELETE CASCADE,
  instrument_id BIGINT REFERENCES instruments(instrument_id) ON DELETE CASCADE,
  topic TEXT,
  kind TEXT NOT NULL CHECK (kind IN ('PRICE_CHANGE','VOLUME_SPIKE','NEWS_CLUSTER','PORTFOLIO_DD','PORTFOLIO_DAY_PNL')),
  window_minutes INT NOT NULL CHECK (window_minutes > 0),
  threshold NUMERIC(20,8) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  cooldown_minutes INT NOT NULL DEFAULT 60,
  hysteresis NUMERIC(10,4) NOT NULL DEFAULT 0.0,
  quiet_hours_json JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alerts_user_instr ON alerts_rules(user_id, instrument_id);

CREATE TABLE alerts_events (
  event_id BIGSERIAL PRIMARY KEY,
  rule_id UUID NOT NULL REFERENCES alerts_rules(rule_id) ON DELETE CASCADE,
  ts TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload JSONB,
  delivered BOOLEAN NOT NULL DEFAULT FALSE,
  muted_reason TEXT
);
CREATE INDEX idx_alerts_events_rule_ts ON alerts_events(rule_id, ts DESC);

CREATE TABLE news_sources (
  source_id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('REGULATOR','EXCHANGE','MEDIA','CRYPTO_MEDIA')),
  domain TEXT UNIQUE NOT NULL,
  rss_url TEXT,
  weight SMALLINT NOT NULL DEFAULT 10,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE news_items (
  item_id BIGSERIAL PRIMARY KEY,
  source_id BIGINT NOT NULL REFERENCES news_sources(source_id) ON DELETE CASCADE,
  url TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  body TEXT,
  published_at TIMESTAMPTZ NOT NULL,
  language CHAR(2) NOT NULL,
  topics TEXT[] NOT NULL DEFAULT '{}',
  tickers TEXT[] NOT NULL DEFAULT '{}',
  hash_fast CHAR(32),
  hash_simhash BIGINT,
  shingle_minhash BYTEA,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_news_items_pub ON news_items(published_at DESC);
CREATE INDEX idx_news_items_hash ON news_items(hash_fast);

CREATE TABLE news_clusters (
  cluster_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  canonical_item_id BIGINT REFERENCES news_items(item_id) ON DELETE SET NULL,
  canonical_url TEXT UNIQUE,
  score NUMERIC(10,4) NOT NULL DEFAULT 0,
  first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
  topics TEXT[] NOT NULL DEFAULT '{}',
  tickers TEXT[] NOT NULL DEFAULT '{}',
  size INT NOT NULL DEFAULT 1,
  cluster_key TEXT UNIQUE
);
CREATE INDEX idx_news_clusters_last_seen ON news_clusters(last_seen DESC);

CREATE TABLE post_stats (
  post_id BIGSERIAL PRIMARY KEY,
  channel_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  cluster_id UUID REFERENCES news_clusters(cluster_id) ON DELETE SET NULL,
  views INT NOT NULL DEFAULT 0,
  posted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (channel_id, message_id)
);
CREATE INDEX idx_post_stats_cluster ON post_stats(cluster_id);

CREATE TABLE cta_clicks (
  click_id BIGSERIAL PRIMARY KEY,
  post_id BIGINT REFERENCES post_stats(post_id) ON DELETE CASCADE,
  cluster_id UUID REFERENCES news_clusters(cluster_id) ON DELETE SET NULL,
  variant TEXT NOT NULL,
  redirect_id UUID NOT NULL,
  clicked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_agent TEXT
);
CREATE INDEX idx_cta_clicks_post ON cta_clicks(post_id, clicked_at DESC);

CREATE TABLE bot_starts (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT,
  payload TEXT,
  post_id BIGINT REFERENCES post_stats(post_id) ON DELETE SET NULL,
  cluster_id UUID REFERENCES news_clusters(cluster_id) ON DELETE SET NULL,
  ab_variant TEXT,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bot_starts_payload ON bot_starts(payload);
