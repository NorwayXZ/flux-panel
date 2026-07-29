SET @ddns_source_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dynamic_dns_rule' AND column_name = 'source_type');
SET @ddns_source_ddl = IF(@ddns_source_exists = 0,
  'ALTER TABLE dynamic_dns_rule ADD COLUMN source_type varchar(16) NOT NULL DEFAULT ''node'' AFTER name', 'SELECT 1');
PREPARE ddns_source_stmt FROM @ddns_source_ddl;
EXECUTE ddns_source_stmt;
DEALLOCATE PREPARE ddns_source_stmt;

SET @ddns_connector_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dynamic_dns_rule' AND column_name = 'connector_id');
SET @ddns_connector_ddl = IF(@ddns_connector_exists = 0,
  'ALTER TABLE dynamic_dns_rule ADD COLUMN connector_id bigint DEFAULT NULL AFTER node_id', 'SELECT 1');
PREPARE ddns_connector_stmt FROM @ddns_connector_ddl;
EXECUTE ddns_connector_stmt;
DEALLOCATE PREPARE ddns_connector_stmt;

SET @ddns_node_nullable = (SELECT IS_NULLABLE FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dynamic_dns_rule' AND column_name = 'node_id');
SET @ddns_node_nullable_ddl = IF(@ddns_node_nullable = 'NO',
  'ALTER TABLE dynamic_dns_rule MODIFY COLUMN node_id bigint DEFAULT NULL', 'SELECT 1');
PREPARE ddns_node_nullable_stmt FROM @ddns_node_nullable_ddl;
EXECUTE ddns_node_nullable_stmt;
DEALLOCATE PREPARE ddns_node_nullable_stmt;

SET @ddns_source_index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'dynamic_dns_rule' AND index_name = 'idx_dynamic_dns_source');
SET @ddns_source_index_ddl = IF(@ddns_source_index_exists = 0,
  'ALTER TABLE dynamic_dns_rule ADD KEY idx_dynamic_dns_source (source_type,node_id,connector_id)', 'SELECT 1');
PREPARE ddns_source_index_stmt FROM @ddns_source_index_ddl;
EXECUTE ddns_source_index_stmt;
DEALLOCATE PREPARE ddns_source_index_stmt;

SET @home_direct_ipv4_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'direct_ipv4');
SET @home_direct_ipv4_ddl = IF(@home_direct_ipv4_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN direct_ipv4 varchar(64) DEFAULT NULL AFTER direct_ipv6', 'SELECT 1');
PREPARE home_direct_ipv4_stmt FROM @home_direct_ipv4_ddl;
EXECUTE home_direct_ipv4_stmt;
DEALLOCATE PREPARE home_direct_ipv4_stmt;

SET @home_ip_checked_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'ip_checked_at');
SET @home_ip_checked_ddl = IF(@home_ip_checked_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN ip_checked_at bigint DEFAULT NULL AFTER ipv6_checked_at', 'SELECT 1');
PREPARE home_ip_checked_stmt FROM @home_ip_checked_ddl;
EXECUTE home_ip_checked_stmt;
DEALLOCATE PREPARE home_ip_checked_stmt;

SET @home_ddns_rule_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'dynamic_dns_rule_id');
SET @home_ddns_rule_ddl = IF(@home_ddns_rule_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN dynamic_dns_rule_id bigint DEFAULT NULL AFTER ip_checked_at', 'SELECT 1');
PREPARE home_ddns_rule_stmt FROM @home_ddns_rule_ddl;
EXECUTE home_ddns_rule_stmt;
DEALLOCATE PREPARE home_ddns_rule_stmt;

SET @home_public_domain_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'public_domain');
SET @home_public_domain_ddl = IF(@home_public_domain_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN public_domain varchar(253) DEFAULT NULL AFTER dynamic_dns_rule_id', 'SELECT 1');
PREPARE home_public_domain_stmt FROM @home_public_domain_ddl;
EXECUTE home_public_domain_stmt;
DEALLOCATE PREPARE home_public_domain_stmt;

SET @home_ddns_index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND index_name = 'idx_home_proxy_ddns');
SET @home_ddns_index_ddl = IF(@home_ddns_index_exists = 0,
  'ALTER TABLE home_proxy_route ADD KEY idx_home_proxy_ddns (dynamic_dns_rule_id)', 'SELECT 1');
PREPARE home_ddns_index_stmt FROM @home_ddns_index_ddl;
EXECUTE home_ddns_index_stmt;
DEALLOCATE PREPARE home_ddns_index_stmt;
