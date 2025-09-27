BEGIN;

-- Ensure demo user
INSERT INTO users (tg_user_id)
VALUES (10001)
ON CONFLICT (tg_user_id) DO NOTHING;

-- Ensure demo portfolio for the user
INSERT INTO portfolios (user_id, name, base_currency)
SELECT user_id, 'Demo RUB', 'RUB'
FROM users
WHERE tg_user_id = 10001
ON CONFLICT (user_id, name) DO NOTHING;

-- Ensure instruments exist
INSERT INTO instruments (class, exchange, board, symbol, currency)
VALUES
    ('EQUITY', 'MOEX', 'TQBR', 'SBER', 'RUB'),
    ('EQUITY', 'MOEX', 'TQBR', 'GAZP', 'RUB'),
    ('CRYPTO', 'CRYPTO', 'SPOT', 'BTCUSDT', 'USDT')
ON CONFLICT (exchange, board, symbol) DO NOTHING;

-- Seed trades for SBER
WITH target_portfolio AS (
    SELECT p.portfolio_id
    FROM portfolios p
    JOIN users u ON u.user_id = p.user_id
    WHERE u.tg_user_id = 10001 AND p.name = 'Demo RUB'
), sber AS (
    SELECT instrument_id FROM instruments
    WHERE exchange = 'MOEX' AND board = 'TQBR' AND symbol = 'SBER'
)
INSERT INTO trades (
    portfolio_id,
    instrument_id,
    datetime,
    side,
    quantity,
    price,
    price_currency,
    fee,
    fee_currency,
    tax,
    tax_currency,
    broker,
    note,
    ext_id
)
SELECT
    target_portfolio.portfolio_id,
    sber.instrument_id,
    TIMESTAMPTZ '2024-01-10T10:15:00+03:00',
    'BUY',
    100.0,
    245.15,
    'RUB',
    1.50,
    'RUB',
    0.00,
    'RUB',
    'Demo Broker',
    'Seed BUY for SBER',
    'demo-sber-buy-1'
FROM target_portfolio, sber
ON CONFLICT (portfolio_id, instrument_id, datetime, side, quantity, price) DO NOTHING;

WITH target_portfolio AS (
    SELECT p.portfolio_id
    FROM portfolios p
    JOIN users u ON u.user_id = p.user_id
    WHERE u.tg_user_id = 10001 AND p.name = 'Demo RUB'
), sber AS (
    SELECT instrument_id FROM instruments
    WHERE exchange = 'MOEX' AND board = 'TQBR' AND symbol = 'SBER'
)
INSERT INTO trades (
    portfolio_id,
    instrument_id,
    datetime,
    side,
    quantity,
    price,
    price_currency,
    fee,
    fee_currency,
    tax,
    tax_currency,
    broker,
    note,
    ext_id
)
SELECT
    target_portfolio.portfolio_id,
    sber.instrument_id,
    TIMESTAMPTZ '2024-03-14T16:05:00+03:00',
    'SELL',
    40.0,
    262.40,
    'RUB',
    1.20,
    'RUB',
    4.10,
    'RUB',
    'Demo Broker',
    'Seed SELL for SBER',
    'demo-sber-sell-1'
FROM target_portfolio, sber
ON CONFLICT (portfolio_id, instrument_id, datetime, side, quantity, price) DO NOTHING;

-- Seed trades for GAZP
WITH target_portfolio AS (
    SELECT p.portfolio_id
    FROM portfolios p
    JOIN users u ON u.user_id = p.user_id
    WHERE u.tg_user_id = 10001 AND p.name = 'Demo RUB'
), gazp AS (
    SELECT instrument_id FROM instruments
    WHERE exchange = 'MOEX' AND board = 'TQBR' AND symbol = 'GAZP'
)
INSERT INTO trades (
    portfolio_id,
    instrument_id,
    datetime,
    side,
    quantity,
    price,
    price_currency,
    fee,
    fee_currency,
    tax,
    tax_currency,
    broker,
    note,
    ext_id
)
SELECT
    target_portfolio.portfolio_id,
    gazp.instrument_id,
    TIMESTAMPTZ '2024-02-08T12:20:00+03:00',
    'BUY',
    70.0,
    158.30,
    'RUB',
    1.05,
    'RUB',
    0.00,
    'RUB',
    'Demo Broker',
    'Seed BUY for GAZP',
    'demo-gazp-buy-1'
FROM target_portfolio, gazp
ON CONFLICT (portfolio_id, instrument_id, datetime, side, quantity, price) DO NOTHING;

WITH target_portfolio AS (
    SELECT p.portfolio_id
    FROM portfolios p
    JOIN users u ON u.user_id = p.user_id
    WHERE u.tg_user_id = 10001 AND p.name = 'Demo RUB'
), gazp AS (
    SELECT instrument_id FROM instruments
    WHERE exchange = 'MOEX' AND board = 'TQBR' AND symbol = 'GAZP'
)
INSERT INTO trades (
    portfolio_id,
    instrument_id,
    datetime,
    side,
    quantity,
    price,
    price_currency,
    fee,
    fee_currency,
    tax,
    tax_currency,
    broker,
    note,
    ext_id
)
SELECT
    target_portfolio.portfolio_id,
    gazp.instrument_id,
    TIMESTAMPTZ '2024-04-05T11:45:00+03:00',
    'SELL',
    50.0,
    166.10,
    'RUB',
    1.10,
    'RUB',
    3.85,
    'RUB',
    'Demo Broker',
    'Seed SELL for GAZP',
    'demo-gazp-sell-1'
FROM target_portfolio, gazp
ON CONFLICT (portfolio_id, instrument_id, datetime, side, quantity, price) DO NOTHING;

-- Seed trades for BTCUSDT (only BUY)
WITH target_portfolio AS (
    SELECT p.portfolio_id
    FROM portfolios p
    JOIN users u ON u.user_id = p.user_id
    WHERE u.tg_user_id = 10001 AND p.name = 'Demo RUB'
), btc AS (
    SELECT instrument_id FROM instruments
    WHERE exchange = 'CRYPTO' AND board = 'SPOT' AND symbol = 'BTCUSDT'
)
INSERT INTO trades (
    portfolio_id,
    instrument_id,
    datetime,
    side,
    quantity,
    price,
    price_currency,
    fee,
    fee_currency,
    tax,
    tax_currency,
    broker,
    note,
    ext_id
)
SELECT
    target_portfolio.portfolio_id,
    btc.instrument_id,
    TIMESTAMPTZ '2024-05-02T09:30:00+00:00',
    'BUY',
    0.15000000,
    30500.00,
    'USDT',
    4.50,
    'USDT',
    0.00,
    'USDT',
    'Demo Broker',
    'Seed BUY for BTCUSDT',
    'demo-btc-buy-1'
FROM target_portfolio, btc
ON CONFLICT (portfolio_id, instrument_id, datetime, side, quantity, price) DO NOTHING;

COMMIT;
