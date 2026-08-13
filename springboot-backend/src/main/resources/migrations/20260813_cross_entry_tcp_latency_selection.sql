-- CloudNest 2.50.6: optional lowest-TCP-latency entry selection.
-- Existing groups retain priority-based primary/backup behaviour because the switch defaults to disabled.

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='tcp_latency_selection_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `tcp_latency_selection_enabled` tinyint NOT NULL DEFAULT 0 AFTER `smart_selection_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='tcp_latency_switch_threshold_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `tcp_latency_switch_threshold_ms` int NOT NULL DEFAULT 5 AFTER `tcp_latency_selection_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
