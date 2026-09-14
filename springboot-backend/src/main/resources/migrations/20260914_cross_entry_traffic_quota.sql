SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_enabled` tinyint NOT NULL DEFAULT 0 AFTER `last_switch_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_limit_bytes');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_limit_bytes` bigint NOT NULL DEFAULT 0 AFTER `traffic_quota_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_reset_day');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_reset_day` tinyint NOT NULL DEFAULT 1 AFTER `traffic_quota_limit_bytes`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_used_bytes');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_used_bytes` bigint NOT NULL DEFAULT 0 AFTER `traffic_quota_reset_day`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_period_start_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_period_start_at` bigint DEFAULT NULL AFTER `traffic_quota_used_bytes`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_exhausted');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_exhausted` tinyint NOT NULL DEFAULT 0 AFTER `traffic_quota_period_start_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_paused');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_paused` tinyint NOT NULL DEFAULT 0 AFTER `traffic_quota_exhausted`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_exhausted_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_exhausted_at` bigint DEFAULT NULL AFTER `traffic_quota_paused`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='traffic_quota_last_error');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `traffic_quota_last_error` varchar(500) DEFAULT NULL AFTER `traffic_quota_exhausted_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
