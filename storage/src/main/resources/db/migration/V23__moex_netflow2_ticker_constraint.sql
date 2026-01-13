DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'moex_netflow2'
    ) THEN
        CREATE TEMPORARY TABLE moex_netflow2_clean ON COMMIT DROP AS
        SELECT DISTINCT ON (date, btrim(ticker))
            date,
            btrim(ticker) AS ticker,
            p30,
            p70,
            p100,
            pv30,
            pv70,
            pv100,
            vol,
            oi
        FROM moex_netflow2
        WHERE ticker IS NOT NULL
          AND btrim(ticker) <> ''
          AND btrim(ticker) !~ '[[:space:]]'
        ORDER BY date, btrim(ticker), ticker;

        TRUNCATE TABLE moex_netflow2;

        INSERT INTO moex_netflow2 (date, ticker, p30, p70, p100, pv30, pv70, pv100, vol, oi)
        SELECT date, ticker, p30, p70, p100, pv30, pv70, pv100, vol, oi
        FROM moex_netflow2_clean;

        ALTER TABLE moex_netflow2 DROP CONSTRAINT IF EXISTS moex_netflow2_ticker_not_blank;
        ALTER TABLE moex_netflow2
            ADD CONSTRAINT moex_netflow2_ticker_not_blank CHECK (ticker ~ '^[^[:space:]]+$');
    END IF;
END $$;
