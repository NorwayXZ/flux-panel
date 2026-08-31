-- Optional link expiry for cross-entry failover groups.
-- NULL preserves the existing permanent-link behaviour.
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema=DATABASE()
                      AND table_name='cross_entry_failover_group'
                      AND column_name='expires_at');
SET @sql := IF(@col_exists=0,
               'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `expires_at` bigint DEFAULT NULL AFTER `enabled`',
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
