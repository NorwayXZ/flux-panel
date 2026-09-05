SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='activity_in_flow');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `activity_in_flow` bigint NOT NULL DEFAULT 0 AFTER `last_telemetry_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='activity_out_flow');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `activity_out_flow` bigint NOT NULL DEFAULT 0 AFTER `activity_in_flow`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='last_in_flow_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `last_in_flow_at` bigint DEFAULT NULL AFTER `activity_out_flow`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='last_out_flow_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `last_out_flow_at` bigint DEFAULT NULL AFTER `last_in_flow_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='last_activity_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `last_activity_at` bigint DEFAULT NULL AFTER `last_out_flow_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

