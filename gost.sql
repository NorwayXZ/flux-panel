-- phpMyAdmin SQL Dump
-- version 5.2.0
-- https://www.phpmyadmin.net/
--
-- 主机： localhost
-- 生成日期： 2025-08-14 21:52:52
-- 服务器版本： 5.7.40-log
-- PHP 版本： 7.4.33

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- 数据库： `gost`
--

-- --------------------------------------------------------

--
-- 表的结构 `forward`
--

CREATE TABLE `forward` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `user_name` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `in_port` int(10) NOT NULL,
  `out_port` int(10) DEFAULT NULL,
  `hop_ports` longtext DEFAULT NULL,
  `remote_addr` longtext NOT NULL,
  `strategy` varchar(100) NOT NULL DEFAULT 'fifo',
  `route_mode` varchar(20) NOT NULL DEFAULT 'single',
  `route_config` longtext DEFAULT NULL,
  `active_tunnel_id` int(10) DEFAULT NULL,
  `protocol_mode` varchar(20) NOT NULL DEFAULT 'tcp_udp',
  `target_health` longtext DEFAULT NULL,
  `last_health_check` bigint(20) DEFAULT NULL,
  `previous_active_tunnel_id` int(10) DEFAULT NULL,
  `last_route_switch` bigint(20) DEFAULT NULL,
  `route_switch_reason` varchar(255) DEFAULT NULL,
  `route_switch_count` int(10) NOT NULL DEFAULT '0',
  `interface_name` varchar(200) DEFAULT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL,
  `inx` int(10) NOT NULL DEFAULT '0'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `forward_route_switch`
--

CREATE TABLE `forward_route_switch` (
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

-- --------------------------------------------------------

--
-- 表的结构 `node`
--

CREATE TABLE `node` (
  `id` int(10) NOT NULL,
  `owner_user_id` int(10) NOT NULL DEFAULT '1',
  `name` varchar(100) NOT NULL,
  `secret` varchar(100) NOT NULL,
  `ip` longtext,
  `server_ip` varchar(100) NOT NULL,
  `port_sta` int(10) NOT NULL,
  `port_end` int(10) NOT NULL,
  `version` varchar(100) DEFAULT NULL,
  `http` int(10) NOT NULL DEFAULT '0',
  `tls` int(10) NOT NULL DEFAULT '0',
  `socks` int(10) NOT NULL DEFAULT '0',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `port_allocation_lock`
--

CREATE TABLE `port_allocation_lock` (
  `id` tinyint(3) unsigned NOT NULL,
  `updated_time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `port_allocation_lock`
--

INSERT INTO `port_allocation_lock` (`id`, `updated_time`) VALUES
(1, 0);

-- --------------------------------------------------------

--
-- 表的结构 `speed_limit`
--

CREATE TABLE `speed_limit` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `speed` int(10) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `tunnel_name` varchar(100) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `statistics_flow`
--

CREATE TABLE `statistics_flow` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `total_flow` bigint(20) NOT NULL,
  `time` varchar(100) NOT NULL,
  `created_time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `tunnel`
--

CREATE TABLE `tunnel` (
  `id` int(10) NOT NULL,
  `owner_user_id` int(10) NOT NULL DEFAULT '1',
  `name` varchar(100) NOT NULL,
  `traffic_ratio` decimal(10,1) NOT NULL DEFAULT '1.0',
  `in_node_id` int(10) NOT NULL,
  `in_ip` varchar(100) NOT NULL,
  `out_node_id` int(10) NOT NULL,
  `out_ip` varchar(100) NOT NULL,
  `node_path` longtext DEFAULT NULL,
  `type` int(10) NOT NULL,
  `protocol` varchar(10) NOT NULL DEFAULT 'tls',
  `flow` int(10) NOT NULL,
  `tcp_listen_addr` varchar(100) NOT NULL DEFAULT '[::]',
  `udp_listen_addr` varchar(100) NOT NULL DEFAULT '[::]',
  `interface_name` varchar(200) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `user`
--

CREATE TABLE `user` (
  `id` int(10) NOT NULL,
  `user` varchar(100) NOT NULL,
  `pwd` varchar(100) NOT NULL,
  `role_id` int(10) NOT NULL,
  `exp_time` bigint(20) DEFAULT NULL,
  `flow` bigint(20) NOT NULL,
  `flow_unlimited` tinyint(1) NOT NULL DEFAULT '0',
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `owned_in_flow` bigint(20) NOT NULL DEFAULT '0',
  `owned_out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_reset_time` bigint(20) NOT NULL,
  `num` int(10) NOT NULL,
  `forward_unlimited` tinyint(1) NOT NULL DEFAULT '0',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `user`
--

INSERT INTO `user` (`id`, `user`, `pwd`, `role_id`, `exp_time`, `flow`, `in_flow`, `out_flow`, `flow_reset_time`, `num`, `created_time`, `updated_time`, `status`) VALUES
(1, 'admin_user', '3c85cdebade1c51cf64ca9f3c09d182d', 0, 2727251700000, 99999, 0, 0, 1, 99999, 1748914865000, 1754011744252, 1);

-- --------------------------------------------------------

--
-- 表的结构 `user_tunnel`
--

CREATE TABLE `user_tunnel` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `speed_id` int(10) DEFAULT NULL,
  `num` int(10) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_reset_time` bigint(20) NOT NULL,
  `exp_time` bigint(20) DEFAULT NULL,
  `flow_unlimited` tinyint(1) NOT NULL DEFAULT '0',
  `forward_unlimited` tinyint(1) NOT NULL DEFAULT '0',
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

CREATE TABLE `user_node` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `node_id` int(10) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  `flow` bigint(20) NOT NULL DEFAULT '0',
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_unlimited` tinyint(1) NOT NULL DEFAULT '1',
  `num` int(10) NOT NULL DEFAULT '0',
  `forward_unlimited` tinyint(1) NOT NULL DEFAULT '1',
  `flow_reset_time` bigint(20) NOT NULL DEFAULT '0',
  `exp_time` bigint(20) DEFAULT NULL,
  `status` tinyint(1) NOT NULL DEFAULT '1',
  UNIQUE KEY `uq_user_node` (`user_id`,`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `vite_config`
--

CREATE TABLE `vite_config` (
  `id` int(10) NOT NULL,
  `name` varchar(200) NOT NULL,
  `value` varchar(200) NOT NULL,
  `time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `layout_preference`
--

CREATE TABLE `layout_preference` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `scope` varchar(64) NOT NULL,
  `item_order` text NOT NULL,
  `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_layout_preference_user_scope` (`user_id`,`scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 告警中心与状态历史
--

CREATE TABLE `monitoring_current` (
  `resource_type` varchar(16) NOT NULL,
  `resource_id` bigint NOT NULL,
  `resource_name` varchar(160) NOT NULL,
  `owner_user_id` int NOT NULL,
  `status` varchar(16) NOT NULL,
  `detail` varchar(500) DEFAULT NULL,
  `changed_at` bigint NOT NULL,
  `checked_at` bigint NOT NULL,
  PRIMARY KEY (`resource_type`,`resource_id`),
  KEY `idx_monitoring_current_owner` (`owner_user_id`,`resource_type`),
  KEY `idx_monitoring_current_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `monitoring_history` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `resource_type` varchar(16) NOT NULL,
  `resource_id` bigint NOT NULL,
  `resource_name` varchar(160) NOT NULL,
  `owner_user_id` int NOT NULL,
  `status` varchar(16) NOT NULL,
  `detail` varchar(500) DEFAULT NULL,
  `started_at` bigint NOT NULL,
  `ended_at` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_monitoring_history_resource` (`resource_type`,`resource_id`,`started_at`),
  KEY `idx_monitoring_history_owner` (`owner_user_id`,`started_at`),
  KEY `idx_monitoring_history_window` (`started_at`,`ended_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `monitoring_alert` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `resource_type` varchar(16) NOT NULL,
  `resource_id` bigint NOT NULL,
  `resource_name` varchar(160) NOT NULL,
  `owner_user_id` int NOT NULL,
  `severity` varchar(16) NOT NULL,
  `status` varchar(16) NOT NULL,
  `title` varchar(200) NOT NULL,
  `detail` varchar(500) DEFAULT NULL,
  `started_at` bigint NOT NULL,
  `resolved_at` bigint DEFAULT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_monitoring_alert_resource` (`resource_type`,`resource_id`,`status`),
  KEY `idx_monitoring_alert_owner` (`owner_user_id`,`status`,`started_at`),
  KEY `idx_monitoring_alert_updated` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `monitoring_alert_read` (
  `alert_id` bigint unsigned NOT NULL,
  `user_id` int NOT NULL,
  `read_at` bigint NOT NULL,
  PRIMARY KEY (`alert_id`,`user_id`),
  KEY `idx_monitoring_alert_read_user` (`user_id`,`read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 内网服务发布、端口池与租约
--

CREATE TABLE `internal_connector` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `user_id` int NOT NULL, `name` varchar(80) NOT NULL,
  `secret` varchar(64) NOT NULL, `allowed_cidrs` varchar(1000) NOT NULL, `version` varchar(40) DEFAULT NULL,
  `remote_ip` varchar(128) DEFAULT NULL, `last_seen` bigint DEFAULT NULL, `status` tinyint NOT NULL DEFAULT 1,
  `created_time` bigint NOT NULL, `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_connector_secret` (`secret`), KEY `idx_connector_user` (`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `port_pool` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `name` varchar(80) NOT NULL, `node_id` bigint NOT NULL,
  `bind_ip` varchar(128) NOT NULL DEFAULT '', `public_host` varchar(255) NOT NULL,
  `start_port` int NOT NULL, `end_port` int NOT NULL, `control_port` int NOT NULL,
  `auth_username` varchar(64) NOT NULL, `auth_password` varchar(64) NOT NULL,
  `default_lease_hours` int NOT NULL DEFAULT 24, `max_lease_hours` int NOT NULL DEFAULT 720,
  `cooldown_seconds` int NOT NULL DEFAULT 60, `status` tinyint NOT NULL DEFAULT 1,
  `created_time` bigint NOT NULL, `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_pool_control` (`node_id`,`bind_ip`,`control_port`), KEY `idx_pool_node` (`node_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `published_service` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `user_id` int NOT NULL, `name` varchar(100) NOT NULL,
  `connector_id` bigint NOT NULL, `pool_id` bigint NOT NULL, `lease_id` bigint DEFAULT NULL,
  `target_host` varchar(255) NOT NULL, `target_port` int NOT NULL, `public_port` int DEFAULT NULL,
  `protocol` varchar(12) NOT NULL DEFAULT 'tcp', `state` varchar(24) NOT NULL, `lease_hours` int NOT NULL,
  `expires_at` bigint DEFAULT NULL, `service_name` varchar(120) DEFAULT NULL, `last_error` varchar(500) DEFAULT NULL,
  `created_time` bigint NOT NULL, `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_service_user` (`user_id`,`state`), KEY `idx_service_expire` (`state`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `port_lease` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `pool_id` bigint NOT NULL, `grant_id` bigint DEFAULT NULL, `service_id` bigint DEFAULT NULL,
  `user_id` int NOT NULL, `port` int NOT NULL, `protocol` varchar(12) NOT NULL, `state` varchar(24) NOT NULL,
  `expires_at` bigint DEFAULT NULL, `release_after` bigint DEFAULT NULL, `created_time` bigint NOT NULL, `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_active_pool_port` (`pool_id`,`protocol`,`port`),
  KEY `idx_lease_expire` (`state`,`expires_at`), KEY `idx_lease_release` (`state`,`release_after`), KEY `idx_lease_grant` (`grant_id`,`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `port_pool_grant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `pool_id` bigint NOT NULL, `user_id` int NOT NULL,
  `start_port` int NOT NULL, `end_port` int NOT NULL, `status` tinyint NOT NULL DEFAULT 1,
  `created_time` bigint NOT NULL, `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_port_grant_user` (`user_id`,`status`), KEY `idx_port_grant_pool` (`pool_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `port_lease_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `lease_id` bigint DEFAULT NULL, `service_id` bigint DEFAULT NULL,
  `user_id` int NOT NULL, `event_type` varchar(32) NOT NULL, `detail` varchar(500) DEFAULT NULL, `created_time` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_lease_event_service` (`service_id`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `service_publish_lock` (`id` int NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO `service_publish_lock` (`id`) VALUES (1);

--
-- 转存表中的数据 `vite_config`
--

INSERT INTO `vite_config` (`id`, `name`, `value`, `time`) VALUES
(1, 'app_name', '云巢 CloudNest', 1755147963000);

--
-- 转储表的索引
--

--
-- 表的索引 `forward`
--
ALTER TABLE `forward`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `node`
--
ALTER TABLE `node`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `port_allocation_lock`
--
ALTER TABLE `port_allocation_lock`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `speed_limit`
--
ALTER TABLE `speed_limit`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `statistics_flow`
--
ALTER TABLE `statistics_flow`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `tunnel`
--
ALTER TABLE `tunnel`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `user`
--
ALTER TABLE `user`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `user_tunnel`
--
ALTER TABLE `user_tunnel`
  ADD PRIMARY KEY (`id`);

ALTER TABLE `user_node`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_user_node` (`user_id`,`node_id`),
  ADD KEY `idx_user_node_node_id` (`node_id`);

--
-- 表的索引 `vite_config`
--
ALTER TABLE `vite_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `name` (`name`);

--
-- 在导出的表使用AUTO_INCREMENT
--

--
-- 使用表AUTO_INCREMENT `forward`
--
ALTER TABLE `forward`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `node`
--
ALTER TABLE `node`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `speed_limit`
--
ALTER TABLE `speed_limit`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `statistics_flow`
--
ALTER TABLE `statistics_flow`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `tunnel`
--
ALTER TABLE `tunnel`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `user`
--
ALTER TABLE `user`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `user_tunnel`
--
ALTER TABLE `user_tunnel`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

ALTER TABLE `user_node`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `vite_config`
--
ALTER TABLE `vite_config`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
