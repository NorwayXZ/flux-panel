-- CloudNest 2.50.7: primary preference tolerance for lowest-TCP-latency selection.

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='tcp_primary_preference_tolerance_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `tcp_primary_preference_tolerance_ms` int NOT NULL DEFAULT 10 AFTER `tcp_latency_switch_threshold_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
