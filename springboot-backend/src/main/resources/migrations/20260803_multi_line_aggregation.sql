CREATE TABLE IF NOT EXISTS aggregation_group (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  name varchar(100) NOT NULL,
  forward_id bigint DEFAULT NULL,
  entry_node_id bigint NOT NULL,
  listen_port int NOT NULL,
  remote_addr varchar(1000) NOT NULL,
  protocol_mode varchar(16) NOT NULL DEFAULT 'tcp_udp',
  mode varchar(16) NOT NULL DEFAULT 'balanced',
  scheduler varchar(16) NOT NULL DEFAULT 'weighted',
  auto_weight tinyint NOT NULL DEFAULT 1,
  minimum_healthy_paths int NOT NULL DEFAULT 1,
  enabled tinyint NOT NULL DEFAULT 1,
  state varchar(24) NOT NULL DEFAULT 'provisioning',
  last_error varchar(500) DEFAULT NULL,
  last_calculated_at bigint DEFAULT NULL,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_aggregation_forward(forward_id),
  KEY idx_aggregation_entry(entry_node_id),
  KEY idx_aggregation_state(enabled,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS aggregation_member (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  group_id bigint NOT NULL,
  tunnel_id bigint NOT NULL,
  manual_weight int NOT NULL DEFAULT 100,
  effective_weight int NOT NULL DEFAULT 100,
  enabled tinyint NOT NULL DEFAULT 1,
  health_status varchar(16) NOT NULL DEFAULT 'unknown',
  bandwidth_mbps decimal(12,3) DEFAULT NULL,
  latency_ms decimal(12,3) DEFAULT NULL,
  packet_loss_percent decimal(9,5) DEFAULT NULL,
  jitter_ms decimal(12,3) DEFAULT NULL,
  metric_measured_at bigint DEFAULT NULL,
  last_checked_at bigint DEFAULT NULL,
  last_error varchar(500) DEFAULT NULL,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_aggregation_member(group_id,tunnel_id),
  KEY idx_aggregation_member_tunnel(tunnel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS aggregation_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  group_id bigint NOT NULL,
  event_type varchar(32) NOT NULL,
  status varchar(16) NOT NULL,
  detail text DEFAULT NULL,
  snapshot_json longtext DEFAULT NULL,
  created_time bigint NOT NULL,
  PRIMARY KEY(id),
  KEY idx_aggregation_event(group_id,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
