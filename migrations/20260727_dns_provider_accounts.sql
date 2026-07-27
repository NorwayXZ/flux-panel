CREATE TABLE IF NOT EXISTS dns_provider_account (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  name varchar(100) NOT NULL,
  provider varchar(32) NOT NULL DEFAULT 'cloudflare',
  api_token varchar(2048) NOT NULL,
  enabled tinyint NOT NULL DEFAULT 1,
  last_sync_at bigint DEFAULT NULL,
  last_error varchar(500) DEFAULT NULL,
  created_by int NOT NULL,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dns_provider_name (provider, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dns_zone (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  account_id bigint NOT NULL,
  provider_zone_id varchar(64) NOT NULL,
  zone_name varchar(253) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'active',
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dns_provider_zone (provider_zone_id),
  KEY idx_dns_zone_account (account_id, zone_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dns_managed_record (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  zone_id bigint NOT NULL,
  provider_record_id varchar(64) NOT NULL,
  fqdn varchar(253) NOT NULL,
  record_type varchar(8) NOT NULL,
  content varchar(255) NOT NULL,
  ttl int NOT NULL DEFAULT 60,
  owner_type varchar(32) DEFAULT NULL,
  owner_id bigint DEFAULT NULL,
  status varchar(24) NOT NULL DEFAULT 'active',
  last_error varchar(500) DEFAULT NULL,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dns_managed_record (zone_id, fqdn, record_type),
  KEY idx_dns_record_owner (owner_type, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @dns_zone_column := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'cross_entry_failover_group'
    AND column_name = 'dns_zone_id'
);
SET @dns_zone_sql := IF(
  @dns_zone_column = 0,
  'ALTER TABLE cross_entry_failover_group ADD COLUMN dns_zone_id bigint DEFAULT NULL AFTER domain',
  'SELECT 1'
);
PREPARE dns_zone_stmt FROM @dns_zone_sql;
EXECUTE dns_zone_stmt;
DEALLOCATE PREPARE dns_zone_stmt;
