-- Активные пользователи за 7 дней по tenant_id
SELECT DISTINCT user_id
FROM events
WHERE ts >= now() - INTERVAL 7 DAY
  AND tenant_id = {tenant:UInt64};
