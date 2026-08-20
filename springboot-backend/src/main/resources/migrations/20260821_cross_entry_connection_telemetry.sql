SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='telemetry_ready');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `telemetry_ready` tinyint NOT NULL DEFAULT 0 AFTER `last_failure_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='total_connections');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `total_connections` bigint NOT NULL DEFAULT 0 AFTER `telemetry_ready`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='current_connections');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `current_connections` bigint NOT NULL DEFAULT 0 AFTER `total_connections`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='reported_total_connections');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `reported_total_connections` bigint NOT NULL DEFAULT 0 AFTER `current_connections`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='last_telemetry_at');
SET @generation_col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='telemetry_generation');
SET @sql := IF(@generation_col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `telemetry_generation` bigint NOT NULL DEFAULT 0 AFTER `reported_total_connections`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `last_telemetry_at` bigint DEFAULT NULL AFTER `telemetry_generation`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@generation_col_exists=0, 'UPDATE `cross_entry_failover_member` SET `telemetry_ready`=0,`total_connections`=0,`current_connections`=0,`reported_total_connections`=0,`telemetry_generation`=0,`last_telemetry_at`=NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND index_name='idx_cross_entry_activity');
SET @sql := IF(@index_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD INDEX `idx_cross_entry_activity` (`forward_id`,`entry_node_id`)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
