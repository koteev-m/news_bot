-- На PRIMARY: слоты и LSN
SELECT slot_name, active, restart_lsn FROM pg_replication_slots;

-- На REPLICA: отставание по времени по таблице-ориентиру (пример usage_events)
SELECT now() - max(occurred_at) AS data_lag FROM usage_events;
