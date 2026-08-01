SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='smart_entry_route' AND column_name='dns_dirty');
SET @column_sql := IF(@column_exists=0, 'ALTER TABLE smart_entry_route ADD COLUMN dns_dirty tinyint NOT NULL DEFAULT 1 AFTER current_address', 'SELECT 1');
PREPARE column_stmt FROM @column_sql; EXECUTE column_stmt; DEALLOCATE PREPARE column_stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='smart_entry_route' AND column_name='applied_ttl');
SET @column_sql := IF(@column_exists=0, 'ALTER TABLE smart_entry_route ADD COLUMN applied_ttl int DEFAULT NULL AFTER dns_dirty', 'SELECT 1');
PREPARE column_stmt FROM @column_sql; EXECUTE column_stmt; DEALLOCATE PREPARE column_stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='smart_entry_route' AND column_name='dns_state');
SET @column_sql := IF(@column_exists=0, 'ALTER TABLE smart_entry_route ADD COLUMN dns_state varchar(24) NOT NULL DEFAULT ''pending'' AFTER applied_ttl', 'SELECT 1');
PREPARE column_stmt FROM @column_sql; EXECUTE column_stmt; DEALLOCATE PREPARE column_stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='smart_entry_route' AND column_name='dns_error');
SET @column_sql := IF(@column_exists=0, 'ALTER TABLE smart_entry_route ADD COLUMN dns_error varchar(500) DEFAULT NULL AFTER dns_state', 'SELECT 1');
PREPARE column_stmt FROM @column_sql; EXECUTE column_stmt; DEALLOCATE PREPARE column_stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='smart_entry_route' AND column_name='dns_verified_at');
SET @column_sql := IF(@column_exists=0, 'ALTER TABLE smart_entry_route ADD COLUMN dns_verified_at bigint DEFAULT NULL AFTER dns_error', 'SELECT 1');
PREPARE column_stmt FROM @column_sql; EXECUTE column_stmt; DEALLOCATE PREPARE column_stmt;

UPDATE smart_entry_route SET dns_dirty=1,dns_state='pending',dns_error=NULL,dns_verified_at=NULL;
UPDATE smart_entry_group SET ttl=600,updated_time=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 WHERE provider='aliyun' AND ttl<600;
