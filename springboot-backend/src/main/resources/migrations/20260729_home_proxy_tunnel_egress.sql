SET @has_egress_mode := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'egress_mode');
SET @sql := IF(@has_egress_mode = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN egress_mode varchar(24) NOT NULL DEFAULT ''single'' AFTER egress_pool_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE home_proxy_route MODIFY COLUMN egress_pool_id bigint DEFAULT NULL;

SET @has_egress_tunnel := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'egress_tunnel_id');
SET @sql := IF(@has_egress_tunnel = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN egress_tunnel_id bigint DEFAULT NULL AFTER egress_mode', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS home_proxy_gateway (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  route_id bigint NOT NULL,
  sequence_no int NOT NULL,
  tunnel_id bigint DEFAULT NULL,
  node_id bigint NOT NULL,
  pool_id bigint DEFAULT NULL,
  grant_id bigint DEFAULT NULL,
  lease_id bigint DEFAULT NULL,
  gateway_port int NOT NULL,
  gateway_name varchar(140) NOT NULL,
  auth_username varchar(80) NOT NULL,
  auth_password varchar(180) NOT NULL,
  created_time bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_home_proxy_gateway_sequence (route_id, sequence_no),
  KEY idx_home_proxy_gateway_route (route_id),
  KEY idx_home_proxy_gateway_lease (lease_id),
  KEY idx_home_proxy_gateway_node (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @has_egress_tunnel_index := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND index_name = 'idx_home_proxy_egress_tunnel');
SET @sql := IF(@has_egress_tunnel_index = 0,
  'ALTER TABLE home_proxy_route ADD KEY idx_home_proxy_egress_tunnel (egress_tunnel_id, state)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
