-- Business ROI (last 30 days)
WITH e AS (
  SELECT ts::date AS d, type, props, user_id
  FROM events
  WHERE ts >= now() - interval '30 days'
),
agg AS (
  SELECT
    d,
    COUNT(*) FILTER (WHERE type='paywall_view') AS paywall_view,
    COUNT(*) FILTER (WHERE type='paywall_cta_click') AS cta,
    COUNT(*) FILTER (WHERE type='stars_payment_succeeded') AS pay
  FROM e GROUP BY 1
),
ctr AS (
  SELECT
    d,
    paywall_view, cta, pay,
    round(100.0*cta/NULLIF(paywall_view,0), 2) AS view_to_cta,
    round(100.0*pay/NULLIF(cta,0), 2) AS cta_to_pay
  FROM agg
)
SELECT * FROM ctr ORDER BY d;

-- ARPPU / ARPU (approx, payment count * avg XTR)
-- При наличии суммы платежа сохраняйте её в props и замените на SUM(props->>'amount')
WITH pay AS (
  SELECT ts::date d, COUNT(*) n
  FROM events
  WHERE type='stars_payment_succeeded'
    AND ts >= now() - interval '30 days'
  GROUP BY 1
),
users AS (
  SELECT ts::date d, COUNT(DISTINCT user_id) dau
  FROM events
  WHERE ts >= now() - interval '30 days'
  GROUP BY 1
)
SELECT
  u.d, u.dau,
  p.n AS pays,
  round(1.0*p.n/NULLIF(u.dau,0), 4) AS arpu_proxy,
  round(NULLIF(p.n,0)::numeric,4)   AS arppu_proxy
FROM users u
LEFT JOIN pay p ON p.d=u.d
ORDER BY u.d;
