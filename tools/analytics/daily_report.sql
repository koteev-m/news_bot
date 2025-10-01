-- Events daily report (UTC)
-- 1) DAU (distinct users) и события по типам за последние 24 часа
WITH e AS (
  SELECT *
  FROM events
  WHERE ts >= now() - interval '24 hours'
)
SELECT
  (SELECT COUNT(DISTINCT user_id) FROM e WHERE user_id IS NOT NULL) AS dau,
  (SELECT COUNT(*) FROM e WHERE type='post_published') AS post_published,
  (SELECT COUNT(*) FROM e WHERE type='cta_click') AS cta_click,
  (SELECT COUNT(*) FROM e WHERE type='miniapp_auth') AS miniapp_auth,
  (SELECT COUNT(*) FROM e WHERE type='stars_payment_succeeded') AS stars_pay_ok,
  (SELECT COUNT(*) FROM e WHERE type='stars_payment_duplicate') AS stars_pay_dup;

-- 2) Conversion funnel за 7 дней (Post -> Click -> Start -> Pay)
WITH e7 AS (
  SELECT *
  FROM events
  WHERE ts >= now() - interval '7 days'
),
funnel AS (
  SELECT
    date_trunc('day', ts) AS d,
    SUM(CASE WHEN type='post_published' THEN 1 ELSE 0 END) AS post,
    SUM(CASE WHEN type='cta_click' THEN 1 ELSE 0 END) AS click,
    SUM(CASE WHEN type='miniapp_auth' THEN 1 ELSE 0 END) AS start,
    SUM(CASE WHEN type='stars_payment_succeeded' THEN 1 ELSE 0 END) AS pay
  FROM e7
  GROUP BY 1
  ORDER BY 1
)
SELECT d::date, post, click, start, pay,
       ROUND(100.0*click/NULLIF(post,0),2)  AS ctr_pc,
       ROUND(100.0*start/NULLIF(click,0),2) AS start_rate_pc,
       ROUND(100.0*pay/NULLIF(start,0),2)   AS pay_rate_pc
FROM funnel;

-- 3) Cohort по неделям первой авторизации miniapp → платёж
WITH first_start AS (
  SELECT user_id, MIN(ts)::date AS first_day
  FROM events
  WHERE type='miniapp_auth' AND user_id IS NOT NULL
  GROUP BY user_id
),
cohort AS (
  SELECT
    date_trunc('week', first_day)::date AS cohort_week,
    COUNT(*) AS users
  FROM first_start
  GROUP BY 1
),
pay_after AS (
  SELECT DISTINCT e.user_id, date_trunc('week', fs.first_day)::date AS cohort_week
  FROM events e
  JOIN first_start fs ON fs.user_id = e.user_id
  WHERE e.type='stars_payment_succeeded'
)
SELECT c.cohort_week,
       c.users AS cohort_users,
       COUNT(pay_after.user_id) AS paid_users,
       ROUND(100.0*COUNT(pay_after.user_id)/NULLIF(c.users,0),2) AS pay_rate_pc
FROM cohort c
LEFT JOIN pay_after USING (cohort_week)
GROUP BY 1,2
ORDER BY 1;
