-- 1) Post -> Click -> Start -> Pay (7d rolling)
WITH e AS (
  SELECT * FROM events WHERE ts >= now() - interval '7 days'
)
SELECT
  date_trunc('day', ts)::date AS day,
  SUM(CASE WHEN type='post_published' THEN 1 ELSE 0 END) AS post,
  SUM(CASE WHEN type='cta_click' THEN 1 ELSE 0 END) AS click,
  SUM(CASE WHEN type='miniapp_auth' THEN 1 ELSE 0 END) AS start,
  SUM(CASE WHEN type='stars_payment_succeeded' THEN 1 ELSE 0 END) AS pay
FROM e
GROUP BY 1
ORDER BY 1;

-- 2) Referral performance (7d)
WITH v AS (
  SELECT * FROM referral_visits WHERE first_seen >= now() - interval '7 days'
)
SELECT ref_code,
       COUNT(*) AS visits,
       COUNT(DISTINCT tg_user_id) FILTER (WHERE tg_user_id IS NOT NULL) AS attached_users
FROM v
GROUP BY ref_code
ORDER BY visits DESC
LIMIT 50;

-- 3) A/B lift (cta_copy: A vs B) — click-through and pay rate
WITH e AS (SELECT * FROM events WHERE ts >= now() - interval '14 days'),
assign AS (SELECT * FROM experiment_assignments WHERE key='cta_copy')
SELECT
  a.variant,
  SUM(CASE WHEN e.type='cta_click' THEN 1 ELSE 0 END) AS clicks,
  SUM(CASE WHEN e.type='stars_payment_succeeded' THEN 1 ELSE 0 END) AS pays
FROM e
JOIN assign a ON e.user_id = a.user_id
GROUP BY a.variant
ORDER BY a.variant;
