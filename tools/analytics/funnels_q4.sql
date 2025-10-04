-- Q4 Funnel Overview (Post -> Click -> Start -> Pay)
-- Requires env: DATABASE_URL (see docs/GROWTH_FUNNELS.md)
\timing on

WITH q4_events AS (
    SELECT
        ts,
        type,
        props,
        COALESCE((props ->> 'ref_source'), 'organic') AS ref_source,
        COALESCE((props ->> 'utm_medium'), 'none') AS utm_medium,
        COALESCE((props ->> 'cta'), 'default') AS cta,
        user_id
    FROM events
    WHERE ts >= DATE '2025-10-01'
      AND ts < DATE '2026-01-01'
),
funnel_base AS (
    SELECT
        DATE_TRUNC('month', ts) AS month,
        ref_source,
        utm_medium,
        COUNT(*) FILTER (WHERE type = 'post_published') AS post,
        COUNT(*) FILTER (WHERE type = 'cta_click') AS click,
        COUNT(*) FILTER (WHERE type = 'miniapp_auth') AS start,
        COUNT(*) FILTER (WHERE type = 'stars_payment_succeeded') AS pay
    FROM q4_events
    GROUP BY 1,2,3
),
monthly_funnel AS (
    SELECT
        month,
        SUM(post) AS post,
        SUM(click) AS click,
        SUM(start) AS start,
        SUM(pay) AS pay,
        ROUND(CASE WHEN post > 0 THEN click::numeric / post * 100 ELSE 0 END, 2) AS post_to_click_pct,
        ROUND(CASE WHEN click > 0 THEN start::numeric / click * 100 ELSE 0 END, 2) AS click_to_start_pct,
        ROUND(CASE WHEN start > 0 THEN pay::numeric / start * 100 ELSE 0 END, 2) AS start_to_pay_pct
    FROM funnel_base
    GROUP BY 1
)
SELECT
    TO_CHAR(month, 'YYYY-MM') AS month,
    post,
    click,
    start,
    pay,
    post_to_click_pct,
    click_to_start_pct,
    start_to_pay_pct
FROM monthly_funnel
ORDER BY month;

-- Segment Breakdown by referral vs paid acquisition
WITH segmented AS (
    SELECT
        DATE_TRUNC('month', ts) AS month,
        CASE
            WHEN ref_source ILIKE 'referral%' THEN 'referral'
            WHEN utm_medium IN ('cpc', 'paid-social', 'paid-search') THEN 'paid'
            ELSE 'organic'
        END AS segment,
        COUNT(*) FILTER (WHERE type = 'post_published') AS post,
        COUNT(*) FILTER (WHERE type = 'cta_click') AS click,
        COUNT(*) FILTER (WHERE type = 'miniapp_auth') AS start,
        COUNT(*) FILTER (WHERE type = 'stars_payment_succeeded') AS pay
    FROM q4_events
    GROUP BY 1,2
)
SELECT
    TO_CHAR(month, 'YYYY-MM') AS month,
    segment,
    post,
    click,
    start,
    pay,
    ROUND(CASE WHEN post > 0 THEN click::numeric / post * 100 ELSE 0 END, 2) AS post_to_click_pct,
    ROUND(CASE WHEN click > 0 THEN start::numeric / click * 100 ELSE 0 END, 2) AS click_to_start_pct,
    ROUND(CASE WHEN start > 0 THEN pay::numeric / start * 100 ELSE 0 END, 2) AS start_to_pay_pct
FROM segmented
ORDER BY month, segment;

-- Conversion by CTA (linking to experiment keys)
SELECT
    TO_CHAR(DATE_TRUNC('month', ts), 'YYYY-MM') AS month,
    cta,
    COUNT(*) FILTER (WHERE type = 'cta_click') AS click,
    COUNT(*) FILTER (WHERE type = 'stars_payment_succeeded') AS pay,
    ROUND(CASE WHEN COUNT(*) FILTER (WHERE type = 'cta_click') > 0
         THEN COUNT(*) FILTER (WHERE type = 'stars_payment_succeeded')::numeric / COUNT(*) FILTER (WHERE type = 'cta_click') * 100
         ELSE 0 END, 2) AS click_to_pay_pct
FROM q4_events
GROUP BY 1,2
ORDER BY month, cta;
