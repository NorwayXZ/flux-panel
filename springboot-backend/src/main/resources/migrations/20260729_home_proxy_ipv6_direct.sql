SET @ingress_nullable = (SELECT IS_NULLABLE FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'ingress_pool_id');
SET @ingress_nullable_ddl = IF(@ingress_nullable = 'NO',
  'ALTER TABLE home_proxy_route MODIFY COLUMN ingress_pool_id bigint DEFAULT NULL', 'SELECT 1');
PREPARE ingress_nullable_stmt FROM @ingress_nullable_ddl;
EXECUTE ingress_nullable_stmt;
DEALLOCATE PREPARE ingress_nullable_stmt;

SET @access_mode_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'access_mode');
SET @access_mode_ddl = IF(@access_mode_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN access_mode varchar(24) NOT NULL DEFAULT ''relay'' AFTER connector_id', 'SELECT 1');
PREPARE access_mode_stmt FROM @access_mode_ddl;
EXECUTE access_mode_stmt;
DEALLOCATE PREPARE access_mode_stmt;

SET @direct_ipv6_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'direct_ipv6');
SET @direct_ipv6_ddl = IF(@direct_ipv6_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN direct_ipv6 varchar(64) DEFAULT NULL AFTER egress_gateway_port', 'SELECT 1');
PREPARE direct_ipv6_stmt FROM @direct_ipv6_ddl;
EXECUTE direct_ipv6_stmt;
DEALLOCATE PREPARE direct_ipv6_stmt;

SET @direct_port_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'direct_port');
SET @direct_port_ddl = IF(@direct_port_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN direct_port int DEFAULT NULL AFTER direct_ipv6', 'SELECT 1');
PREPARE direct_port_stmt FROM @direct_port_ddl;
EXECUTE direct_port_stmt;
DEALLOCATE PREPARE direct_port_stmt;

SET @ipv6_checked_at_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'home_proxy_route' AND column_name = 'ipv6_checked_at');
SET @ipv6_checked_at_ddl = IF(@ipv6_checked_at_exists = 0,
  'ALTER TABLE home_proxy_route ADD COLUMN ipv6_checked_at bigint DEFAULT NULL AFTER direct_port', 'SELECT 1');
PREPARE ipv6_checked_at_stmt FROM @ipv6_checked_at_ddl;
EXECUTE ipv6_checked_at_stmt;
DEALLOCATE PREPARE ipv6_checked_at_stmt;
