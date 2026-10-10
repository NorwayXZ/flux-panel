-- Smart Entry reliability additions for existing installations.
-- The startup schema initializer applies the same additions automatically.
ALTER TABLE smart_entry_group
  ADD COLUMN recovery_stable_ms int NOT NULL DEFAULT 30000,
  ADD COLUMN switch_cooldown_ms int NOT NULL DEFAULT 60000,
  ADD COLUMN probe_mode varchar(16) NOT NULL DEFAULT 'tcp',
  ADD COLUMN probe_path varchar(255) NOT NULL DEFAULT '/',
  ADD COLUMN sync_requested tinyint NOT NULL DEFAULT 0;

ALTER TABLE smart_entry_route
  ADD COLUMN fallback_carriers varchar(128) DEFAULT NULL,
  ADD COLUMN healthy_since bigint DEFAULT NULL,
  ADD COLUMN last_switched_at bigint DEFAULT NULL,
  ADD COLUMN ownership_ready tinyint NOT NULL DEFAULT 0,
  ADD COLUMN dns_target_address varchar(128) DEFAULT NULL,
  ADD COLUMN dns_attempted_at bigint DEFAULT NULL;

CREATE TABLE IF NOT EXISTS smart_entry_dns_cleanup (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  group_id bigint NOT NULL,
  payload text NOT NULL,
  last_error varchar(500) DEFAULT NULL,
  attempted_at bigint DEFAULT NULL,
  created_time bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_smart_cleanup_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS smart_entry_activity_archive (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  group_id bigint NOT NULL,
  forward_id bigint NOT NULL,
  entry_node_id bigint NOT NULL,
  node_name varchar(100) NOT NULL,
  entry_address varchar(128) NOT NULL,
  total_connections bigint NOT NULL,
  in_flow bigint NOT NULL,
  out_flow bigint NOT NULL,
  last_activity_at bigint DEFAULT NULL,
  archived_at bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_smart_archive_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
