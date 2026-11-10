-- Track entry availability alerts and the DNS address left behind when the active entry's node is deleted.
-- Compatible with MySQL 5.7 and MySQL 8; safe to run more than once.
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                    AND table_name='cross_entry_failover_group' AND column_name='availability_alert_signature');
SET @sql := IF(@col_exists=0,
               'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `availability_alert_signature` varchar(255) DEFAULT NULL',
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                    AND table_name='cross_entry_failover_group' AND column_name='detached_active_address');
SET @sql := IF(@col_exists=0,
               'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `detached_active_address` varchar(128) DEFAULT NULL',
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
