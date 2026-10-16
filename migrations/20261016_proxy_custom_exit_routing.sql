-- CloudNest 2.76.0: custom exits and per-domain routing for private proxies.
-- Run once only when maintaining the database manually. Normal panel startup is idempotent.

ALTER TABLE `private_proxy`
  ADD COLUMN `exit_custom_id` bigint DEFAULT NULL,
  ADD COLUMN `route_rules` text DEFAULT NULL,
  ADD COLUMN `route_countries` varchar(255) DEFAULT NULL,
  ADD COLUMN `route_exit_ports` varchar(500) DEFAULT NULL;

CREATE TABLE IF NOT EXISTS `proxy_exit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `name` varchar(100) NOT NULL,
  `protocol` varchar(32) NOT NULL,
  `server` varchar(253) NOT NULL,
  `port` int NOT NULL,
  `outbound` text NOT NULL,
  `last_test_at` bigint DEFAULT NULL,
  `last_test_ok` tinyint DEFAULT NULL,
  `last_test_ip` varchar(64) DEFAULT NULL,
  `last_test_country` varchar(8) DEFAULT NULL,
  `last_test_latency` int DEFAULT NULL,
  `last_test_error` varchar(500) DEFAULT NULL,
  `created_time` bigint NOT NULL,
  `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_proxy_exit_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
