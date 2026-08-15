-- CloudNest 2.51.18: cross-entry failover manual lock expiry and rejected-backup cooldown.

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='post_switch_reject_suppress_seconds');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `post_switch_reject_suppress_seconds` int NOT NULL DEFAULT 600 AFTER `post_switch_verify_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='manual_lock_until');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `manual_lock_until` bigint DEFAULT NULL AFTER `locked_member_id`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='switch_rejected_until');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `switch_rejected_until` bigint DEFAULT NULL AFTER `quality_recovery_observe_until`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='switch_rejected_reason');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `switch_rejected_reason` varchar(255) DEFAULT NULL AFTER `switch_rejected_until`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='switch_reject_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `switch_reject_count` int NOT NULL DEFAULT 0 AFTER `switch_rejected_reason`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
