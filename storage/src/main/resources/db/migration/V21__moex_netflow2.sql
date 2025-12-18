CREATE TABLE IF NOT EXISTS moex_netflow2 (
    date DATE NOT NULL,
    ticker TEXT NOT NULL,
    p30 BIGINT,
    p70 BIGINT,
    p100 BIGINT,
    pv30 BIGINT,
    pv70 BIGINT,
    pv100 BIGINT,
    vol BIGINT,
    oi BIGINT,
    PRIMARY KEY (date, ticker)
);

CREATE INDEX IF NOT EXISTS idx_moex_netflow2_ticker_date ON moex_netflow2 (ticker, date);

COMMENT ON TABLE moex_netflow2 IS 'Netflow2 daily aggregates from MOEX; upserts are idempotent due to PK(date, ticker)';
