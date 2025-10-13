-- Риск оттока: не заходили 14+ дней
SELECT DISTINCT user_id
FROM users
WHERE last_active_at < now() - INTERVAL 14 DAY
  AND tenant_id = {tenant:UInt64};
