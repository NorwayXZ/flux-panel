SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='fault_episode_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `fault_episode_count` int NOT NULL DEFAULT 0 AFTER `quality_checked_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='connect_fault_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `connect_fault_count` int NOT NULL DEFAULT 0 AFTER `fault_episode_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='latency_fault_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `latency_fault_count` int NOT NULL DEFAULT 0 AFTER `connect_fault_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='loss_fault_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `loss_fault_count` int NOT NULL DEFAULT 0 AFTER `latency_fault_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='p95_fault_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `p95_fault_count` int NOT NULL DEFAULT 0 AFTER `loss_fault_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='jitter_fault_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `jitter_fault_count` int NOT NULL DEFAULT 0 AFTER `p95_fault_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='flap_fault_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `flap_fault_count` int NOT NULL DEFAULT 0 AFTER `jitter_fault_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='switch_trigger_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `switch_trigger_count` int NOT NULL DEFAULT 0 AFTER `flap_fault_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='last_fault_type');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `last_fault_type` varchar(32) DEFAULT NULL AFTER `switch_trigger_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='last_fault_reason');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `last_fault_reason` varchar(255) DEFAULT NULL AFTER `last_fault_type`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='last_fault_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `last_fault_at` bigint DEFAULT NULL AFTER `last_fault_reason`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
