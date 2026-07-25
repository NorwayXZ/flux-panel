SET @terminal_enabled_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'node'
      AND column_name = 'terminal_enabled'
);
SET @terminal_enabled_ddl = IF(
    @terminal_enabled_exists = 0,
    'ALTER TABLE `node` ADD COLUMN `terminal_enabled` tinyint NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE terminal_enabled_statement FROM @terminal_enabled_ddl;
EXECUTE terminal_enabled_statement;
DEALLOCATE PREPARE terminal_enabled_statement;

CREATE TABLE IF NOT EXISTS `terminal_session_audit` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `session_id` varchar(64) NOT NULL,
    `user_id` int NOT NULL,
    `username` varchar(80) NOT NULL,
    `node_id` bigint NOT NULL,
    `node_name` varchar(160) NOT NULL,
    `source_ip` varchar(128) DEFAULT NULL,
    `status` varchar(24) NOT NULL,
    `close_reason` varchar(160) DEFAULT NULL,
    `started_at` bigint NOT NULL,
    `ended_at` bigint DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_terminal_session` (`session_id`),
    KEY `idx_terminal_node_time` (`node_id`, `started_at`),
    KEY `idx_terminal_user_time` (`user_id`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
