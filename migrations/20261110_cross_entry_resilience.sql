-- Persist uncertain single-entry DNS switches and avoid rewriting unchanged DNS pools.
-- Compatible with MySQL 5.7 and MySQL 8; safe to run more than once.
CREATE TABLE IF NOT EXISTS cross_entry_switch_pending (
  group_id bigint NOT NULL,
  from_member_id bigint DEFAULT NULL,
  to_member_id bigint NOT NULL,
  from_address varchar(128) DEFAULT NULL,
  to_address varchar(128) NOT NULL,
  reason varchar(255) NOT NULL,
  phase varchar(24) NOT NULL DEFAULT 'applying',
  last_error varchar(500) DEFAULT NULL,
  attempts int NOT NULL DEFAULT 0,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cross_entry_pool_sync_state (
  group_id bigint NOT NULL,
  desired_signature text NOT NULL,
  last_verified_at bigint NOT NULL,
  PRIMARY KEY (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cross_entry_pool_create_pending (
  group_id bigint NOT NULL,
  member_id bigint NOT NULL,
  content varchar(255) NOT NULL,
  tag varchar(128) NOT NULL,
  started_at bigint NOT NULL,
  PRIMARY KEY (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                    AND table_name='cross_entry_failover_member' AND column_name='address_refresh_error');
SET @sql := IF(@col_exists=0,
               'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `address_refresh_error` varchar(500) DEFAULT NULL AFTER `entry_address`',
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
