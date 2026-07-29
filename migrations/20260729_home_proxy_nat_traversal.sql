SET @nat_source_connector_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'source_connector_id');
SET @nat_source_connector_ddl = IF(@nat_source_connector_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN source_connector_id bigint DEFAULT NULL AFTER connector_id', 'SELECT 1');
PREPARE nat_source_connector_stmt FROM @nat_source_connector_ddl;
EXECUTE nat_source_connector_stmt;
DEALLOCATE PREPARE nat_source_connector_stmt;

SET @nat_source_port_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'source_listen_port');
SET @nat_source_port_ddl = IF(@nat_source_port_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN source_listen_port int DEFAULT NULL AFTER direct_port', 'SELECT 1');
PREPARE nat_source_port_stmt FROM @nat_source_port_ddl;
EXECUTE nat_source_port_stmt;
DEALLOCATE PREPARE nat_source_port_stmt;

SET @nat_backend_port_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'nat_backend_port');
SET @nat_backend_port_ddl = IF(@nat_backend_port_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN nat_backend_port int DEFAULT NULL AFTER source_listen_port', 'SELECT 1');
PREPARE nat_backend_port_stmt FROM @nat_backend_port_ddl;
EXECUTE nat_backend_port_stmt;
DEALLOCATE PREPARE nat_backend_port_stmt;

SET @nat_state_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'nat_state');
SET @nat_state_ddl = IF(@nat_state_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN nat_state varchar(24) DEFAULT NULL AFTER nat_backend_port', 'SELECT 1');
PREPARE nat_state_stmt FROM @nat_state_ddl;
EXECUTE nat_state_stmt;
DEALLOCATE PREPARE nat_state_stmt;

SET @nat_path_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'active_access_path');
SET @nat_path_ddl = IF(@nat_path_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN active_access_path varchar(24) DEFAULT NULL AFTER nat_state', 'SELECT 1');
PREPARE nat_path_stmt FROM @nat_path_ddl;
EXECUTE nat_path_stmt;
DEALLOCATE PREPARE nat_path_stmt;

SET @nat_type_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'nat_type');
SET @nat_type_ddl = IF(@nat_type_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN nat_type varchar(48) DEFAULT NULL AFTER active_access_path', 'SELECT 1');
PREPARE nat_type_stmt FROM @nat_type_ddl;
EXECUTE nat_type_stmt;
DEALLOCATE PREPARE nat_type_stmt;

SET @nat_success_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'direct_success_count');
SET @nat_success_ddl = IF(@nat_success_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN direct_success_count bigint NOT NULL DEFAULT 0 AFTER nat_type', 'SELECT 1');
PREPARE nat_success_stmt FROM @nat_success_ddl;
EXECUTE nat_success_stmt;
DEALLOCATE PREPARE nat_success_stmt;

SET @nat_failure_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'direct_failure_count');
SET @nat_failure_ddl = IF(@nat_failure_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN direct_failure_count bigint NOT NULL DEFAULT 0 AFTER direct_success_count', 'SELECT 1');
PREPARE nat_failure_stmt FROM @nat_failure_ddl;
EXECUTE nat_failure_stmt;
DEALLOCATE PREPARE nat_failure_stmt;

SET @nat_direct_rx_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'direct_rx_bytes');
SET @nat_direct_rx_ddl = IF(@nat_direct_rx_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN direct_rx_bytes bigint NOT NULL DEFAULT 0 AFTER direct_failure_count', 'SELECT 1');
PREPARE nat_direct_rx_stmt FROM @nat_direct_rx_ddl;
EXECUTE nat_direct_rx_stmt;
DEALLOCATE PREPARE nat_direct_rx_stmt;

SET @nat_direct_tx_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'direct_tx_bytes');
SET @nat_direct_tx_ddl = IF(@nat_direct_tx_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN direct_tx_bytes bigint NOT NULL DEFAULT 0 AFTER direct_rx_bytes', 'SELECT 1');
PREPARE nat_direct_tx_stmt FROM @nat_direct_tx_ddl;
EXECUTE nat_direct_tx_stmt;
DEALLOCATE PREPARE nat_direct_tx_stmt;

SET @nat_relay_rx_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'relay_rx_bytes');
SET @nat_relay_rx_ddl = IF(@nat_relay_rx_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN relay_rx_bytes bigint NOT NULL DEFAULT 0 AFTER direct_tx_bytes', 'SELECT 1');
PREPARE nat_relay_rx_stmt FROM @nat_relay_rx_ddl;
EXECUTE nat_relay_rx_stmt;
DEALLOCATE PREPARE nat_relay_rx_stmt;

SET @nat_relay_tx_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'relay_tx_bytes');
SET @nat_relay_tx_ddl = IF(@nat_relay_tx_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN relay_tx_bytes bigint NOT NULL DEFAULT 0 AFTER relay_rx_bytes', 'SELECT 1');
PREPARE nat_relay_tx_stmt FROM @nat_relay_tx_ddl;
EXECUTE nat_relay_tx_stmt;
DEALLOCATE PREPARE nat_relay_tx_stmt;

SET @nat_probe_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'last_nat_probe_at');
SET @nat_probe_ddl = IF(@nat_probe_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN last_nat_probe_at bigint DEFAULT NULL AFTER relay_tx_bytes', 'SELECT 1');
PREPARE nat_probe_stmt FROM @nat_probe_ddl;
EXECUTE nat_probe_stmt;
DEALLOCATE PREPARE nat_probe_stmt;

SET @nat_switch_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'last_path_switch_at');
SET @nat_switch_ddl = IF(@nat_switch_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN last_path_switch_at bigint DEFAULT NULL AFTER last_nat_probe_at', 'SELECT 1');
PREPARE nat_switch_stmt FROM @nat_switch_ddl;
EXECUTE nat_switch_stmt;
DEALLOCATE PREPARE nat_switch_stmt;

SET @nat_error_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'last_nat_error');
SET @nat_error_ddl = IF(@nat_error_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN last_nat_error varchar(500) DEFAULT NULL AFTER last_path_switch_at', 'SELECT 1');
PREPARE nat_error_stmt FROM @nat_error_ddl;
EXECUTE nat_error_stmt;
DEALLOCATE PREPARE nat_error_stmt;

SET @nat_source_index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND index_name = 'idx_home_proxy_source_connector');
SET @nat_source_index_ddl = IF(@nat_source_index_exists = 0,
  'ALTER TABLE home_proxy_route ADD KEY idx_home_proxy_source_connector (source_connector_id, state)', 'SELECT 1');
PREPARE nat_source_index_stmt FROM @nat_source_index_ddl;
EXECUTE nat_source_index_stmt;
DEALLOCATE PREPARE nat_source_index_stmt;

CREATE TABLE IF NOT EXISTS home_proxy_nat_event (
  id bigint NOT NULL AUTO_INCREMENT,
  route_id bigint NOT NULL,
  user_id int NOT NULL,
  event_type varchar(32) NOT NULL,
  access_path varchar(24) DEFAULT NULL,
  detail varchar(500) DEFAULT NULL,
  created_time bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_nat_event_route (route_id, created_time),
  KEY idx_nat_event_user (user_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
