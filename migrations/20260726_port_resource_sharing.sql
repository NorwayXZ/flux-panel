CREATE TABLE IF NOT EXISTS port_pool_grant (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  pool_id bigint NOT NULL,
  user_id int NOT NULL,
  start_port int NOT NULL,
  end_port int NOT NULL,
  status tinyint NOT NULL DEFAULT 1,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_port_grant_user (user_id, status),
  KEY idx_port_grant_pool (pool_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @port_lease_grant_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'port_lease' AND column_name = 'grant_id'
);
SET @port_lease_grant_ddl = IF(
  @port_lease_grant_exists = 0,
  'ALTER TABLE port_lease ADD COLUMN grant_id bigint DEFAULT NULL AFTER pool_id, ADD KEY idx_lease_grant (grant_id, state)',
  'SELECT 1'
);
PREPARE port_lease_grant_stmt FROM @port_lease_grant_ddl;
EXECUTE port_lease_grant_stmt;
DEALLOCATE PREPARE port_lease_grant_stmt;
