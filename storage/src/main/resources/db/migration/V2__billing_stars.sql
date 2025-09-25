-- Billing plans reference table
CREATE TABLE IF NOT EXISTS billing_plans (
    plan_id SERIAL PRIMARY KEY,
    tier TEXT NOT NULL UNIQUE CHECK (tier IN ('FREE', 'PRO', 'PRO_PLUS', 'VIP')),
    title TEXT NOT NULL,
    price_xtr BIGINT NOT NULL CHECK (price_xtr >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_billing_plans_active_tier ON billing_plans (is_active, tier);

-- User subscriptions for Stars provider
CREATE TABLE IF NOT EXISTS user_subscriptions (
    user_id BIGINT NOT NULL,
    tier TEXT NOT NULL CHECK (tier IN ('FREE', 'PRO', 'PRO_PLUS', 'VIP')),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED', 'PENDING')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    provider TEXT NOT NULL DEFAULT 'STARS',
    last_payment_id TEXT,
    PRIMARY KEY (user_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_status ON user_subscriptions (user_id, status);

-- Incoming payments journal for Stars
CREATE TABLE IF NOT EXISTS star_payments (
    payment_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    tier TEXT NOT NULL CHECK (tier IN ('PRO', 'PRO_PLUS', 'VIP')),
    amount_xtr BIGINT NOT NULL CHECK (amount_xtr >= 0),
    provider_payment_charge_id TEXT NOT NULL UNIQUE,
    invoice_payload TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status TEXT NOT NULL CHECK (status IN ('SUCCEEDED', 'REJECTED', 'PENDING'))
);

CREATE INDEX IF NOT EXISTS idx_star_payments_user_created ON star_payments (user_id, created_at DESC);
