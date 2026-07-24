ALTER TABLE `forward`
  ADD COLUMN `route_mode` varchar(20) NOT NULL DEFAULT 'single' AFTER `strategy`,
  ADD COLUMN `route_config` longtext DEFAULT NULL AFTER `route_mode`,
  ADD COLUMN `active_tunnel_id` int(10) DEFAULT NULL AFTER `route_config`,
  ADD COLUMN `protocol_mode` varchar(20) NOT NULL DEFAULT 'tcp_udp' AFTER `active_tunnel_id`,
  ADD COLUMN `target_health` longtext DEFAULT NULL AFTER `protocol_mode`,
  ADD COLUMN `last_health_check` bigint(20) DEFAULT NULL AFTER `target_health`;
