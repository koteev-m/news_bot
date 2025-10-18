-- enable logical replication
ALTER SYSTEM SET wal_level = logical;
ALTER SYSTEM SET max_wal_senders = 10;
ALTER SYSTEM SET max_replication_slots = 10;
SELECT pg_reload_conf();

-- app user (read/write), repl user (replication)
DO $$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'repl') THEN
      CREATE ROLE repl WITH REPLICATION LOGIN PASSWORD 'REPL_PASS';
   END IF;
END$$;

-- publication (tables to replicate)
DROP PUBLICATION IF EXISTS newsbot_pub;
CREATE PUBLICATION newsbot_pub FOR TABLE
  public.portfolios,
  public.alerts_rules,
  public.user_subscriptions,
  public.users;
