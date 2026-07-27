CREATE TABLE IF NOT EXISTS private_proxy (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  user_id int NOT NULL,
  name varchar(100) NOT NULL,
  node_id bigint NOT NULL,
  proxy_type varchar(32) NOT NULL,
  bind_ip varchar(128) NOT NULL DEFAULT '',
  listen_port int NOT NULL,
  auth_username varchar(64) NOT NULL,
  auth_password text NOT NULL,
  allowed_cidrs varchar(1000) NOT NULL DEFAULT '',
  state varchar(24) NOT NULL,
  expires_at bigint DEFAULT NULL,
  service_name varchar(120) NOT NULL,
  admission_name varchar(120) DEFAULT NULL,
  client_config text DEFAULT NULL,
  last_error varchar(500) DEFAULT NULL,
  in_flow bigint NOT NULL DEFAULT 0,
  out_flow bigint NOT NULL DEFAULT 0,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_private_proxy_user (user_id, state),
  KEY idx_private_proxy_node_port (node_id, listen_port, state),
  KEY idx_private_proxy_expiry (state, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE private_proxy MODIFY COLUMN proxy_type varchar(32) NOT NULL;
