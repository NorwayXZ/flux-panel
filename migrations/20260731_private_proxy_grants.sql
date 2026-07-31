SET @grantor_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='private_proxy' AND column_name='granted_by_user_id');
SET @grantor_sql = IF(@grantor_exists=0, 'ALTER TABLE private_proxy ADD COLUMN granted_by_user_id int DEFAULT NULL AFTER user_id', 'SELECT 1');
PREPARE stmt FROM @grantor_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @flow_limit_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='private_proxy' AND column_name='flow_limit');
SET @flow_limit_sql = IF(@flow_limit_exists=0, 'ALTER TABLE private_proxy ADD COLUMN flow_limit bigint NOT NULL DEFAULT 0 AFTER out_flow', 'SELECT 1');
PREPARE stmt FROM @flow_limit_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @flow_unlimited_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='private_proxy' AND column_name='flow_unlimited');
SET @flow_unlimited_sql = IF(@flow_unlimited_exists=0, 'ALTER TABLE private_proxy ADD COLUMN flow_unlimited tinyint NOT NULL DEFAULT 1 AFTER flow_limit', 'SELECT 1');
PREPARE stmt FROM @flow_unlimited_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @flow_reset_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='private_proxy' AND column_name='flow_reset_day');
SET @flow_reset_sql = IF(@flow_reset_exists=0, 'ALTER TABLE private_proxy ADD COLUMN flow_reset_day tinyint NOT NULL DEFAULT 0 AFTER flow_unlimited', 'SELECT 1');
PREPARE stmt FROM @flow_reset_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @last_reset_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='private_proxy' AND column_name='last_flow_reset_at');
SET @last_reset_sql = IF(@last_reset_exists=0, 'ALTER TABLE private_proxy ADD COLUMN last_flow_reset_at bigint DEFAULT NULL AFTER flow_reset_day', 'SELECT 1');
PREPARE stmt FROM @last_reset_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @speed_limit_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='private_proxy' AND column_name='speed_limit_mbps');
SET @speed_limit_sql = IF(@speed_limit_exists=0, 'ALTER TABLE private_proxy ADD COLUMN speed_limit_mbps int DEFAULT NULL AFTER last_flow_reset_at', 'SELECT 1');
PREPARE stmt FROM @speed_limit_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @grant_index_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='private_proxy' AND index_name='idx_private_proxy_grant');
SET @grant_index_sql = IF(@grant_index_exists=0, 'ALTER TABLE private_proxy ADD KEY idx_private_proxy_grant (granted_by_user_id,user_id,state)', 'SELECT 1');
PREPARE stmt FROM @grant_index_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
