SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND column_name='rule_type');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD COLUMN `rule_type` varchar(24) NOT NULL DEFAULT ''carrier'' AFTER `carrier`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND column_name='rule_name');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD COLUMN `rule_name` varchar(100) DEFAULT NULL AFTER `rule_type`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND column_name='priority');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD COLUMN `priority` int NOT NULL DEFAULT 100 AFTER `rule_name`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND column_name='region');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD COLUMN `region` varchar(100) DEFAULT NULL AFTER `cidrs`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND column_name='asn');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD COLUMN `asn` varchar(64) DEFAULT NULL AFTER `region`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND column_name='tags');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD COLUMN `tags` varchar(255) DEFAULT NULL AFTER `asn`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND column_name='quality_policy');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD COLUMN `quality_policy` varchar(24) NOT NULL DEFAULT ''static'' AFTER `tags`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND column_name='notes');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD COLUMN `notes` varchar(500) DEFAULT NULL AFTER `quality_policy`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE source_ip_entry_route SET rule_type='default' WHERE carrier='default' AND rule_type='carrier';
UPDATE source_ip_entry_route SET rule_type='cidr' WHERE carrier='custom' AND rule_type='carrier';

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND index_name='uk_source_ip_entry_carrier');
SET @sql := IF(@idx_exists>0, 'ALTER TABLE `source_ip_entry_route` DROP INDEX `uk_source_ip_entry_carrier`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='source_ip_entry_route' AND index_name='idx_source_ip_entry_carrier');
SET @sql := IF(@idx_exists=0, 'ALTER TABLE `source_ip_entry_route` ADD INDEX `idx_source_ip_entry_carrier` (`group_id`,`carrier`)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS source_ip_asn_database (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  asn varchar(64) NOT NULL,
  cidrs longtext NOT NULL,
  ipv4_count int NOT NULL DEFAULT 0,
  ipv6_count int NOT NULL DEFAULT 0,
  prefix_count int NOT NULL DEFAULT 0,
  source_url text,
  last_error varchar(500) DEFAULT NULL,
  state varchar(24) NOT NULL DEFAULT 'pending',
  updated_time bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_source_ip_asn (asn),
  KEY idx_source_ip_asn_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
