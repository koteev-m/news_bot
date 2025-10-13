CREATE DATABASE IF NOT EXISTS newsbot;

CREATE TABLE IF NOT EXISTS newsbot.portfolios_kafka
(
  tenant_id UInt64,
  portfolio_id UInt64,
  name String,
  created_at DateTime
) ENGINE = Kafka
SETTINGS kafka_broker_list = 'redpanda:9092',
         kafka_topic_list = 'pg.public.portfolios',
         kafka_group_name = 'ch-portfolios',
         kafka_format = 'JSONEachRow';

CREATE TABLE IF NOT EXISTS newsbot.portfolios
(
  tenant_id UInt64,
  portfolio_id UInt64,
  name String,
  created_at DateTime,
  _ingested_at DateTime DEFAULT now()
) ENGINE = MergeTree
ORDER BY (tenant_id, portfolio_id);

CREATE MATERIALIZED VIEW IF NOT EXISTS newsbot.portfolios_mv
TO newsbot.portfolios
AS SELECT tenant_id, portfolio_id, name, parseDateTimeBestEffort(created_at) as created_at
FROM newsbot.portfolios_kafka;

-- Alerts (пример)
CREATE TABLE IF NOT EXISTS newsbot.alerts_rules_kafka
(
  tenant_id UInt64,
  rule_id UInt64,
  symbol String,
  threshold Float64,
  created_at DateTime
) ENGINE = Kafka
SETTINGS kafka_broker_list = 'redpanda:9092',
         kafka_topic_list = 'pg.public.alerts_rules',
         kafka_group_name = 'ch-alerts',
         kafka_format = 'JSONEachRow';

CREATE TABLE IF NOT EXISTS newsbot.alerts_rules
(
  tenant_id UInt64,
  rule_id UInt64,
  symbol String,
  threshold Float64,
  created_at DateTime,
  _ingested_at DateTime DEFAULT now()
) ENGINE = MergeTree
ORDER BY (tenant_id, rule_id);

CREATE MATERIALIZED VIEW IF NOT EXISTS newsbot.alerts_rules_mv
TO newsbot.alerts_rules
AS SELECT tenant_id, rule_id, symbol, threshold, parseDateTimeBestEffort(created_at) as created_at
FROM newsbot.alerts_rules_kafka;
