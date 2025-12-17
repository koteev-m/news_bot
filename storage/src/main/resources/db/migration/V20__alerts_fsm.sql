CREATE TABLE IF NOT EXISTS alerts_fsm_state (
    user_id BIGINT PRIMARY KEY,
    state_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS alerts_daily_budget (
    user_id BIGINT PRIMARY KEY,
    day DATE NOT NULL,
    push_count INT NOT NULL CHECK (push_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
