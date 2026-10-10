-- Normal upgrades apply this additive schema automatically at startup.
-- Run ALTER only on an installation without connector_role.
ALTER TABLE internal_connector ADD COLUMN connector_role varchar(24) NOT NULL DEFAULT 'service';
CREATE TABLE IF NOT EXISTS openwrt_dns_resolver (
  connector_id bigint NOT NULL,
  user_id int NOT NULL,
  interface_carriers text NOT NULL,
  smart_entry_group_ids text NOT NULL,
  policy_revision bigint NOT NULL DEFAULT 1,
  applied_revision bigint NOT NULL DEFAULT 0,
  active_interface varchar(64) DEFAULT NULL,
  active_carrier varchar(24) NOT NULL DEFAULT 'default',
  resolved_queries bigint NOT NULL DEFAULT 0,
  dnsmasq_reloaded tinyint NOT NULL DEFAULT 0,
  last_error varchar(500) DEFAULT NULL,
  reported_at bigint DEFAULT NULL,
  status_json text NULL,
  policy_hash varchar(64) DEFAULT NULL,
  sync_error varchar(500) DEFAULT NULL,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY(connector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
