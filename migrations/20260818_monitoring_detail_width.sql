SET @col_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='monitoring_current' AND column_name='detail');
SET @sql := IF(@col_len IS NOT NULL AND @col_len < 500, 'ALTER TABLE `monitoring_current` MODIFY COLUMN `detail` varchar(500) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='monitoring_history' AND column_name='detail');
SET @sql := IF(@col_len IS NOT NULL AND @col_len < 500, 'ALTER TABLE `monitoring_history` MODIFY COLUMN `detail` varchar(500) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='monitoring_alert' AND column_name='detail');
SET @sql := IF(@col_len IS NOT NULL AND @col_len < 500, 'ALTER TABLE `monitoring_alert` MODIFY COLUMN `detail` varchar(500) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
