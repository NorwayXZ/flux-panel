package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class ServicePublishingSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public ServicePublishingSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS internal_connector ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(80) NOT NULL, "
                    + "secret varchar(64) NOT NULL, allowed_cidrs varchar(1000) NOT NULL, platform varchar(16) NOT NULL DEFAULT 'linux', "
                    + "version varchar(40) DEFAULT NULL, "
                    + "remote_ip varchar(128) DEFAULT NULL, last_seen bigint DEFAULT NULL, status tinyint NOT NULL DEFAULT 1, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), UNIQUE KEY uk_connector_secret (secret), "
                    + "KEY idx_connector_user (user_id, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS port_pool ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, name varchar(80) NOT NULL, node_id bigint NOT NULL, "
                    + "bind_ip varchar(128) NOT NULL DEFAULT '', public_host varchar(255) NOT NULL, start_port int NOT NULL, end_port int NOT NULL, "
                    + "control_port int NOT NULL, auth_username varchar(64) NOT NULL, auth_password varchar(64) NOT NULL, "
                    + "default_lease_hours int NOT NULL DEFAULT 24, max_lease_hours int NOT NULL DEFAULT 720, cooldown_seconds int NOT NULL DEFAULT 60, "
                    + "status tinyint NOT NULL DEFAULT 1, created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_pool_control (node_id, bind_ip, control_port), KEY idx_pool_node (node_id, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS published_service ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(100) NOT NULL, connector_id bigint NOT NULL, "
                    + "pool_id bigint NOT NULL, lease_id bigint DEFAULT NULL, target_host varchar(255) NOT NULL, target_port int NOT NULL, "
                    + "public_port int DEFAULT NULL, protocol varchar(12) NOT NULL DEFAULT 'tcp', state varchar(24) NOT NULL, "
                    + "lease_hours int NOT NULL, expires_at bigint DEFAULT NULL, service_name varchar(120) DEFAULT NULL, last_error varchar(500) DEFAULT NULL, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), KEY idx_service_user (user_id, state), "
                    + "KEY idx_service_expire (state, expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS port_lease ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, pool_id bigint NOT NULL, service_id bigint DEFAULT NULL, user_id int NOT NULL, "
                    + "port int NOT NULL, protocol varchar(12) NOT NULL, state varchar(24) NOT NULL, expires_at bigint DEFAULT NULL, release_after bigint DEFAULT NULL, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), UNIQUE KEY uk_active_pool_port (pool_id, protocol, port), "
                    + "KEY idx_lease_expire (state, expires_at), KEY idx_lease_release (state, release_after)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS port_lease_event ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, lease_id bigint DEFAULT NULL, service_id bigint DEFAULT NULL, user_id int NOT NULL, "
                    + "event_type varchar(32) NOT NULL, detail varchar(500) DEFAULT NULL, created_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "KEY idx_lease_event_service (service_id, created_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS port_pool_grant ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, pool_id bigint NOT NULL, user_id int NOT NULL, "
                    + "start_port int NOT NULL, end_port int NOT NULL, status tinyint NOT NULL DEFAULT 1, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "KEY idx_port_grant_user (user_id, status), KEY idx_port_grant_pool (pool_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS domain_route ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(100) NOT NULL, "
                    + "domain varchar(253) NOT NULL, path_prefix varchar(255) NOT NULL DEFAULT '/', published_service_id bigint DEFAULT NULL, "
                    + "backend_type varchar(24) NOT NULL DEFAULT 'mapping', backend_node_id bigint DEFAULT NULL, backend_host varchar(128) DEFAULT NULL, "
                    + "backend_port int DEFAULT NULL, backend_scheme varchar(12) NOT NULL DEFAULT 'http', backend_path varchar(255) NOT NULL DEFAULT '/', node_id bigint NOT NULL, "
                    + "listen_port int NOT NULL DEFAULT 443, service_name varchar(120) NOT NULL, state varchar(24) NOT NULL, "
                    + "last_error varchar(500) DEFAULT NULL, health_state varchar(24) NOT NULL DEFAULT 'pending', health_status_code int DEFAULT NULL, "
                    + "health_latency_ms bigint DEFAULT NULL, health_checked_at bigint DEFAULT NULL, health_error varchar(500) DEFAULT NULL, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, "
                    + "PRIMARY KEY (id), UNIQUE KEY uk_domain_node_port (node_id, listen_port, domain), "
                    + "KEY idx_domain_user (user_id, state), KEY idx_domain_entry (node_id, listen_port, state), "
                    + "KEY idx_domain_mapping (published_service_id, state)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS managed_certificate ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, zone_id bigint NOT NULL, domain varchar(253) NOT NULL, "
                    + "account_key text DEFAULT NULL, private_key text DEFAULT NULL, certificate_chain text DEFAULT NULL, "
                    + "issuer varchar(160) DEFAULT NULL, serial_number varchar(160) DEFAULT NULL, not_before bigint DEFAULT NULL, expires_at bigint DEFAULT NULL, "
                    + "state varchar(24) NOT NULL DEFAULT 'pending', last_error varchar(500) DEFAULT NULL, last_attempt_at bigint DEFAULT NULL, "
                    + "next_attempt_at bigint DEFAULT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_managed_certificate_domain (zone_id,domain), KEY idx_certificate_renewal (state,expires_at,next_attempt_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS service_publish_lock (id int NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.update("INSERT IGNORE INTO service_publish_lock (id) VALUES (1)");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS home_proxy_route ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(100) NOT NULL, "
                    + "connector_id bigint NOT NULL, access_mode varchar(24) NOT NULL DEFAULT 'relay', ingress_pool_id bigint DEFAULT NULL, egress_pool_id bigint DEFAULT NULL, "
                    + "lease_id bigint DEFAULT NULL, public_port int DEFAULT NULL, egress_lease_id bigint DEFAULT NULL, "
                    + "egress_gateway_port int DEFAULT NULL, direct_ipv6 varchar(64) DEFAULT NULL, direct_ipv4 varchar(64) DEFAULT NULL, direct_port int DEFAULT NULL, "
                    + "ipv6_checked_at bigint DEFAULT NULL, ip_checked_at bigint DEFAULT NULL, dynamic_dns_rule_id bigint DEFAULT NULL, public_domain varchar(253) DEFAULT NULL, "
                    + "proxy_type varchar(16) NOT NULL DEFAULT 'socks5', "
                    + "auth_enabled tinyint NOT NULL DEFAULT 0, auth_username varchar(64) DEFAULT NULL, auth_password varchar(128) DEFAULT NULL, "
                    + "state varchar(24) NOT NULL DEFAULT 'provisioning', last_error varchar(500) DEFAULT NULL, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "KEY idx_home_proxy_user (user_id, state), KEY idx_home_proxy_connector (connector_id, state), "
                    + "KEY idx_home_proxy_lease (lease_id), KEY idx_home_proxy_egress_lease (egress_lease_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("home_proxy_route", "egress_lease_id", "bigint DEFAULT NULL AFTER public_port");
            ensureColumn("home_proxy_route", "egress_gateway_port", "int DEFAULT NULL AFTER egress_lease_id");
            ensureColumn("home_proxy_route", "access_mode", "varchar(24) NOT NULL DEFAULT 'relay' AFTER connector_id");
            ensureColumn("home_proxy_route", "direct_ipv6", "varchar(64) DEFAULT NULL AFTER egress_gateway_port");
            ensureColumn("home_proxy_route", "direct_ipv4", "varchar(64) DEFAULT NULL AFTER direct_ipv6");
            ensureColumn("home_proxy_route", "direct_port", "int DEFAULT NULL AFTER direct_ipv4");
            ensureColumn("home_proxy_route", "ipv6_checked_at", "bigint DEFAULT NULL AFTER direct_port");
            ensureColumn("home_proxy_route", "ip_checked_at", "bigint DEFAULT NULL AFTER ipv6_checked_at");
            ensureColumn("home_proxy_route", "dynamic_dns_rule_id", "bigint DEFAULT NULL AFTER ip_checked_at");
            ensureColumn("home_proxy_route", "public_domain", "varchar(253) DEFAULT NULL AFTER dynamic_dns_rule_id");
            ensureColumn("home_proxy_route", "egress_mode", "varchar(24) NOT NULL DEFAULT 'single' AFTER egress_pool_id");
            ensureColumn("home_proxy_route", "egress_tunnel_id", "bigint DEFAULT NULL AFTER egress_mode");
            ensureColumn("home_proxy_route", "egress_node_id", "bigint DEFAULT NULL AFTER egress_pool_id");
            ensureColumn("home_proxy_route", "transport_mode", "varchar(24) NOT NULL DEFAULT 'standard_tcp' AFTER egress_tunnel_id");
            ensureColumn("home_proxy_route", "reality_server_name", "varchar(253) DEFAULT NULL AFTER transport_mode");
            ensureColumn("home_proxy_route", "source_connector_id", "bigint DEFAULT NULL AFTER connector_id");
            ensureColumn("home_proxy_route", "source_listen_port", "int DEFAULT NULL AFTER direct_port");
            ensureColumn("home_proxy_route", "nat_backend_port", "int DEFAULT NULL AFTER source_listen_port");
            ensureColumn("home_proxy_route", "nat_state", "varchar(24) DEFAULT NULL AFTER nat_backend_port");
            ensureColumn("home_proxy_route", "active_access_path", "varchar(24) DEFAULT NULL AFTER nat_state");
            ensureColumn("home_proxy_route", "nat_type", "varchar(48) DEFAULT NULL AFTER active_access_path");
            ensureColumn("home_proxy_route", "direct_success_count", "bigint NOT NULL DEFAULT 0 AFTER nat_type");
            ensureColumn("home_proxy_route", "direct_failure_count", "bigint NOT NULL DEFAULT 0 AFTER direct_success_count");
            ensureColumn("home_proxy_route", "direct_rx_bytes", "bigint NOT NULL DEFAULT 0 AFTER direct_failure_count");
            ensureColumn("home_proxy_route", "direct_tx_bytes", "bigint NOT NULL DEFAULT 0 AFTER direct_rx_bytes");
            ensureColumn("home_proxy_route", "relay_rx_bytes", "bigint NOT NULL DEFAULT 0 AFTER direct_tx_bytes");
            ensureColumn("home_proxy_route", "relay_tx_bytes", "bigint NOT NULL DEFAULT 0 AFTER relay_rx_bytes");
            ensureColumn("home_proxy_route", "last_nat_probe_at", "bigint DEFAULT NULL AFTER relay_tx_bytes");
            ensureColumn("home_proxy_route", "last_path_switch_at", "bigint DEFAULT NULL AFTER last_nat_probe_at");
            ensureColumn("home_proxy_route", "last_nat_error", "varchar(500) DEFAULT NULL AFTER last_path_switch_at");
            ensureNullableColumn("home_proxy_route", "ingress_pool_id", "bigint DEFAULT NULL");
            ensureNullableColumn("home_proxy_route", "egress_pool_id", "bigint DEFAULT NULL");
            ensureIndex("home_proxy_route", "idx_home_proxy_egress_lease", "egress_lease_id");
            ensureIndex("home_proxy_route", "idx_home_proxy_ddns", "dynamic_dns_rule_id");
            ensureIndex("home_proxy_route", "idx_home_proxy_egress_tunnel", "egress_tunnel_id, state");
            ensureIndex("home_proxy_route", "idx_home_proxy_egress_node", "egress_node_id, state");
            ensureIndex("home_proxy_route", "idx_home_proxy_source_connector", "source_connector_id, state");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS home_proxy_nat_event ("
                    + "id bigint NOT NULL AUTO_INCREMENT, route_id bigint NOT NULL, user_id int NOT NULL, "
                    + "event_type varchar(32) NOT NULL, access_path varchar(24) DEFAULT NULL, detail varchar(500) DEFAULT NULL, "
                    + "created_time bigint NOT NULL, PRIMARY KEY (id), KEY idx_nat_event_route (route_id, created_time), "
                    + "KEY idx_nat_event_user (user_id, created_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS home_proxy_gateway ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, route_id bigint NOT NULL, sequence_no int NOT NULL, "
                    + "tunnel_id bigint DEFAULT NULL, node_id bigint NOT NULL, pool_id bigint DEFAULT NULL, grant_id bigint DEFAULT NULL, "
                    + "lease_id bigint DEFAULT NULL, gateway_port int NOT NULL, gateway_name varchar(140) NOT NULL, "
                    + "auth_username varchar(80) NOT NULL, auth_password varchar(180) NOT NULL, created_time bigint NOT NULL, "
                    + "PRIMARY KEY (id), UNIQUE KEY uk_home_proxy_gateway_sequence (route_id, sequence_no), "
                    + "KEY idx_home_proxy_gateway_route (route_id), KEY idx_home_proxy_gateway_lease (lease_id), "
                    + "KEY idx_home_proxy_gateway_node (node_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureNullableColumn("home_proxy_gateway", "pool_id", "bigint DEFAULT NULL");
            ensureNullableColumn("home_proxy_gateway", "lease_id", "bigint DEFAULT NULL");
            ensureColumn("home_proxy_gateway", "gateway_type", "varchar(24) NOT NULL DEFAULT 'socks5' AFTER gateway_name");
            ensureColumn("home_proxy_gateway", "runtime_name", "varchar(140) DEFAULT NULL AFTER gateway_type");
            ensureColumn("internal_connector", "platform", "varchar(16) NOT NULL DEFAULT 'linux'");
            ensureColumn("port_lease", "grant_id", "bigint DEFAULT NULL AFTER pool_id");
            ensureIndex("port_lease", "idx_lease_grant", "grant_id, state");
            ensureColumn("domain_route", "ingress_mode", "varchar(24) NOT NULL DEFAULT 'passthrough' AFTER service_name");
            ensureColumn("domain_route", "dns_zone_id", "bigint DEFAULT NULL AFTER ingress_mode");
            ensureColumn("domain_route", "dns_record_id", "varchar(64) DEFAULT NULL AFTER dns_zone_id");
            ensureColumn("domain_route", "certificate_id", "bigint DEFAULT NULL AFTER dns_record_id");
            ensureColumn("domain_route", "path_prefix", "varchar(255) NOT NULL DEFAULT '/' AFTER domain");
            ensureNullableColumn("domain_route", "published_service_id", "bigint DEFAULT NULL");
            ensureColumn("domain_route", "backend_type", "varchar(24) NOT NULL DEFAULT 'mapping' AFTER published_service_id");
            ensureColumn("domain_route", "backend_node_id", "bigint DEFAULT NULL AFTER backend_type");
            ensureColumn("domain_route", "backend_host", "varchar(128) DEFAULT NULL AFTER backend_node_id");
            ensureColumn("domain_route", "backend_port", "int DEFAULT NULL AFTER backend_host");
            ensureColumn("domain_route", "backend_scheme", "varchar(12) NOT NULL DEFAULT 'http' AFTER backend_port");
            ensureColumn("domain_route", "backend_path", "varchar(255) NOT NULL DEFAULT '/' AFTER backend_scheme");
            ensureColumn("domain_route", "health_state", "varchar(24) NOT NULL DEFAULT 'pending' AFTER last_error");
            ensureColumn("domain_route", "health_status_code", "int DEFAULT NULL AFTER health_state");
            ensureColumn("domain_route", "health_latency_ms", "bigint DEFAULT NULL AFTER health_status_code");
            ensureColumn("domain_route", "health_checked_at", "bigint DEFAULT NULL AFTER health_latency_ms");
            ensureColumn("domain_route", "health_error", "varchar(500) DEFAULT NULL AFTER health_checked_at");
            replaceDomainRouteUniqueIndex();
            ensureIndex("domain_route", "idx_domain_certificate", "certificate_id, state");
            ensureIndex("domain_route", "idx_domain_backend_node", "backend_node_id, state");
            ensureIndex("domain_route", "idx_domain_health", "state, health_checked_at");
        } catch (DataAccessException e) {
            log.error("Service publishing storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }

    private void ensureNullableColumn(String table, String column, String definition) {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
        if (!"YES".equalsIgnoreCase(nullable)) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` " + definition);
        }
    }

    private void ensureIndex(String table, String index, String columns) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD KEY `" + index + "` (" + columns + ")");
        }
    }

    private void replaceDomainRouteUniqueIndex() {
        Integer oldIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='domain_route' AND index_name='uk_domain_node_port'",
                Integer.class);
        if (oldIndex != null && oldIndex > 0) {
            jdbcTemplate.execute("ALTER TABLE domain_route DROP INDEX uk_domain_node_port");
        }
        ensureUniqueIndex("domain_route", "uk_domain_node_port_path", "node_id, listen_port, domain, path_prefix");
    }

    private void ensureUniqueIndex(String table, String index, String columns) {
        Integer nonUnique = jdbcTemplate.queryForObject(
                "SELECT MAX(NON_UNIQUE) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (nonUnique != null && nonUnique != 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` DROP INDEX `" + index + "`");
            nonUnique = null;
        }
        if (nonUnique == null) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD UNIQUE KEY `" + index + "` (" + columns + ")");
        }
    }
}
