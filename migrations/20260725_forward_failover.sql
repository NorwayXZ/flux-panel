ALTER TABLE `forward`
  ADD COLUMN `previous_active_tunnel_id` int(10) DEFAULT NULL AFTER `last_health_check`,
  ADD COLUMN `last_route_switch` bigint(20) DEFAULT NULL AFTER `previous_active_tunnel_id`,
  ADD COLUMN `route_switch_reason` varchar(255) DEFAULT NULL AFTER `last_route_switch`,
  ADD COLUMN `route_switch_count` int(10) NOT NULL DEFAULT '0' AFTER `route_switch_reason`;

CREATE TABLE IF NOT EXISTS `forward_route_switch` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `forward_id` bigint(20) NOT NULL,
  `user_id` int(10) NOT NULL,
  `from_tunnel_id` int(10) DEFAULT NULL,
  `from_tunnel_name` varchar(160) DEFAULT NULL,
  `to_tunnel_id` int(10) DEFAULT NULL,
  `to_tunnel_name` varchar(160) DEFAULT NULL,
  `reason` varchar(255) NOT NULL,
  `trigger_type` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL,
  `detail` varchar(255) DEFAULT NULL,
  `created_at` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_forward_route_switch_forward` (`forward_id`, `created_at`),
  KEY `idx_forward_route_switch_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
