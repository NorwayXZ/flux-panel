SET @home_egress_node_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'egress_node_id');
SET @home_egress_node_ddl = IF(@home_egress_node_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN egress_node_id bigint DEFAULT NULL AFTER egress_pool_id', 'SELECT 1');
PREPARE home_egress_node_stmt FROM @home_egress_node_ddl;
EXECUTE home_egress_node_stmt;
DEALLOCATE PREPARE home_egress_node_stmt;

SET @home_transport_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'transport_mode');
SET @home_transport_ddl = IF(@home_transport_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN transport_mode varchar(24) NOT NULL DEFAULT ''standard_tcp'' AFTER egress_tunnel_id', 'SELECT 1');
PREPARE home_transport_stmt FROM @home_transport_ddl;
EXECUTE home_transport_stmt;
DEALLOCATE PREPARE home_transport_stmt;

SET @home_reality_sni_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'reality_server_name');
SET @home_reality_sni_ddl = IF(@home_reality_sni_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN reality_server_name varchar(253) DEFAULT NULL AFTER transport_mode', 'SELECT 1');
PREPARE home_reality_sni_stmt FROM @home_reality_sni_ddl;
EXECUTE home_reality_sni_stmt;
DEALLOCATE PREPARE home_reality_sni_stmt;

SET @home_gateway_type_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_gateway' AND column_name = 'gateway_type');
SET @home_gateway_type_ddl = IF(@home_gateway_type_exists = 0,
  'ALTER TABLE home_proxy_gateway ADD COLUMN gateway_type varchar(24) NOT NULL DEFAULT ''socks5'' AFTER gateway_name', 'SELECT 1');
PREPARE home_gateway_type_stmt FROM @home_gateway_type_ddl;
EXECUTE home_gateway_type_stmt;
DEALLOCATE PREPARE home_gateway_type_stmt;

SET @home_runtime_name_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_gateway' AND column_name = 'runtime_name');
SET @home_runtime_name_ddl = IF(@home_runtime_name_exists = 0,
  'ALTER TABLE home_proxy_gateway ADD COLUMN runtime_name varchar(140) DEFAULT NULL AFTER gateway_type', 'SELECT 1');
PREPARE home_runtime_name_stmt FROM @home_runtime_name_ddl;
EXECUTE home_runtime_name_stmt;
DEALLOCATE PREPARE home_runtime_name_stmt;

SET @home_egress_node_index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND index_name = 'idx_home_proxy_egress_node');
SET @home_egress_node_index_ddl = IF(@home_egress_node_index_exists = 0,
  'ALTER TABLE home_proxy_route ADD KEY idx_home_proxy_egress_node (egress_node_id, state)', 'SELECT 1');
PREPARE home_egress_node_index_stmt FROM @home_egress_node_index_ddl;
EXECUTE home_egress_node_index_stmt;
DEALLOCATE PREPARE home_egress_node_index_stmt;
