SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_member_daily_usage' AND column_name='traffic_active_millis');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_member_daily_usage` ADD COLUMN `traffic_active_millis` bigint NOT NULL DEFAULT 0 AFTER `active_millis`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
