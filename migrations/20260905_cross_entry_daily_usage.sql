SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='active_since_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `active_since_at` bigint DEFAULT NULL AFTER `active_member_id`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `cross_entry_member_daily_usage` (
  `group_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `usage_date` date NOT NULL,
  `active_millis` bigint NOT NULL DEFAULT 0,
  `updated_time` bigint NOT NULL,
  PRIMARY KEY (`group_id`, `member_id`, `usage_date`),
  KEY `idx_cross_entry_daily_usage` (`group_id`, `usage_date`, `active_millis`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE `cross_entry_failover_group`
SET `active_since_at`=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE `enabled`=1 AND `active_member_id` IS NOT NULL AND `active_since_at` IS NULL;

