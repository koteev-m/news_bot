DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'moex_netflow2'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'moex_netflow2'
              AND column_name = 'sec'
        ) THEN
            IF NOT EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = 'moex_netflow2_legacy'
            ) THEN
                EXECUTE 'ALTER TABLE moex_netflow2 RENAME TO moex_netflow2_legacy';
            ELSE
                EXECUTE 'DROP TABLE moex_netflow2';
            END IF;
        END IF;
    END IF;
END $$;

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
    CONSTRAINT moex_netflow2_ticker_not_blank CHECK (ticker ~ '^[^[:space:]]+$'),
    PRIMARY KEY (date, ticker)
);

CREATE INDEX IF NOT EXISTS idx_moex_netflow2_ticker_date ON moex_netflow2 (ticker, date);

COMMENT ON TABLE moex_netflow2 IS 'Netflow2 daily aggregates from MOEX; upserts are idempotent due to PK(date, ticker)';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'moex_netflow2'
    ) THEN
        EXECUTE 'UPDATE moex_netflow2 SET ticker = btrim(ticker) WHERE ticker IS NOT NULL';
        EXECUTE 'DELETE FROM moex_netflow2 WHERE ticker IS NULL OR ticker = '''' OR ticker ~ ''[[:space:]]''';
        EXECUTE 'ALTER TABLE moex_netflow2 DROP CONSTRAINT IF EXISTS moex_netflow2_ticker_not_blank';
        EXECUTE 'ALTER TABLE moex_netflow2
                 ADD CONSTRAINT moex_netflow2_ticker_not_blank
                 CHECK (ticker ~ ''^[^[:space:]]+$'')';
    END IF;
END $$;
