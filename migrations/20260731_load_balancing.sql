-- CloudNest 2.37.0: three-stage load balancing.
-- Run once only when maintaining the database manually. Normal panel startup is idempotent.

ALTER TABLE `forward`
  ADD COLUMN `route_balance_strategy` varchar(24) NOT NULL DEFAULT 'round' AFTER `route_mode`;

ALTER TABLE `domain_route`
  ADD COLUMN `backend_strategy` varchar(24) NOT NULL DEFAULT 'round' AFTER `backend_path`,
  ADD COLUMN `session_affinity` varchar(24) NOT NULL DEFAULT 'none' AFTER `backend_strategy`;

CREATE TABLE IF NOT EXISTS `domain_route_backend` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `route_id` bigint NOT NULL,
  `position` int NOT NULL DEFAULT 0,
  `name` varchar(100) NOT NULL,
  `backend_type` varchar(24) NOT NULL DEFAULT 'direct',
  `published_service_id` bigint DEFAULT NULL,
  `backend_node_id` bigint DEFAULT NULL,
  `backend_host` varchar(128) DEFAULT NULL,
  `backend_port` int DEFAULT NULL,
  `backend_scheme` varchar(12) NOT NULL DEFAULT 'http',
  `backend_path` varchar(255) NOT NULL DEFAULT '/',
  `weight` int NOT NULL DEFAULT 100,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `health_state` varchar(24) NOT NULL DEFAULT 'pending',
  `fail_count` int NOT NULL DEFAULT 0,
  `success_count` int NOT NULL DEFAULT 0,
  `health_latency_ms` bigint DEFAULT NULL,
  `health_error` varchar(500) DEFAULT NULL,
  `health_checked_at` bigint DEFAULT NULL,
  `created_time` bigint NOT NULL,
  `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_domain_backend_position` (`route_id`,`position`),
  KEY `idx_domain_backend_health` (`enabled`,`health_checked_at`),
  KEY `idx_domain_backend_route` (`route_id`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `domain_route_backend`
  (`route_id`,`position`,`name`,`backend_type`,`published_service_id`,`backend_node_id`,`backend_host`,`backend_port`,`backend_scheme`,`backend_path`,`weight`,`enabled`,`health_state`,`created_time`,`updated_time`)
SELECT `id`,0,'默认后端',`backend_type`,`published_service_id`,`backend_node_id`,`backend_host`,`backend_port`,`backend_scheme`,`backend_path`,100,1,'pending',`created_time`,`updated_time`
FROM `domain_route`
WHERE `ingress_mode`='managed_https' AND `state`<>'deleted';

ALTER TABLE `cross_entry_failover_group`
  ADD COLUMN `routing_mode` varchar(24) NOT NULL DEFAULT 'failover' AFTER `auto_failback`;

ALTER TABLE `cross_entry_failover_member`
  ADD COLUMN `weight` int NOT NULL DEFAULT 100 AFTER `priority`,
  ADD COLUMN `enabled` tinyint NOT NULL DEFAULT 1 AFTER `weight`;

CREATE TABLE IF NOT EXISTS `cross_entry_dns_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `group_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `provider_record_id` varchar(64) NOT NULL,
  `content` varchar(255) NOT NULL,
  `created_time` bigint NOT NULL,
  `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cross_entry_dns_member` (`group_id`,`member_id`),
  UNIQUE KEY `uk_cross_entry_dns_provider` (`provider_record_id`),
  KEY `idx_cross_entry_dns_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
