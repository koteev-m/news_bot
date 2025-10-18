-- On replica, create subscription
-- Replace PRIMARY_HOST, PRIMARY_PORT, REPL_PASS accordingly
DROP SUBSCRIPTION IF EXISTS newsbot_sub;
CREATE SUBSCRIPTION newsbot_sub
  CONNECTION 'host=PRIMARY_HOST port=5432 user=repl password=REPL_PASS dbname=newsbot'
  PUBLICATION newsbot_pub
  WITH (create_slot = true, slot_name = 'newsbot_slot', copy_data = true);
