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
    CONSTRAINT moex_netflow2_ticker_not_blank CHECK (btrim(ticker) <> ''),
    PRIMARY KEY (date, ticker)
);

CREATE INDEX IF NOT EXISTS idx_moex_netflow2_ticker_date ON moex_netflow2 (ticker, date);

COMMENT ON TABLE moex_netflow2 IS 'Netflow2 daily aggregates from MOEX; upserts are idempotent due to PK(date, ticker)';

DO $$
DECLARE
    ticker_constraint_def TEXT;
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'moex_netflow2'
    ) THEN
        SELECT lower(pg_get_constraintdef(c.oid))
        INTO ticker_constraint_def
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema()
          AND t.relname = 'moex_netflow2'
          AND c.conname = 'moex_netflow2_ticker_not_blank'
          AND c.contype = 'c'
        LIMIT 1;

        IF ticker_constraint_def IS NULL THEN
            EXECUTE 'ALTER TABLE moex_netflow2
                     ADD CONSTRAINT moex_netflow2_ticker_not_blank
                     CHECK (btrim(ticker) <> '''')';
        ELSIF ticker_constraint_def !~ E'btrim[[:space:]]*\\([[:space:]]*ticker'
           OR ticker_constraint_def !~ E'<>[[:space:]]*''''(::text)?'
        THEN
            EXECUTE 'ALTER TABLE moex_netflow2 DROP CONSTRAINT moex_netflow2_ticker_not_blank';
            EXECUTE 'ALTER TABLE moex_netflow2
                     ADD CONSTRAINT moex_netflow2_ticker_not_blank
                     CHECK (btrim(ticker) <> '''')';
        END IF;
    END IF;
END $$;
