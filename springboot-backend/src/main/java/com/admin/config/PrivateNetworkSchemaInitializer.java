package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class PrivateNetworkSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public PrivateNetworkSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS private_network_group ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,name varchar(100) NOT NULL,network_type varchar(24) NOT NULL,"
                    + "cidr varchar(64) DEFAULT NULL,state varchar(24) NOT NULL DEFAULT 'pending',last_error varchar(500) DEFAULT NULL,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),UNIQUE KEY uk_private_network_name(name)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS private_network_member ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,group_id bigint NOT NULL,node_id bigint NOT NULL,private_address varchar(45) NOT NULL,"
                    + "interface_name varchar(64) DEFAULT NULL,mtu int NOT NULL DEFAULT 1500,created_time bigint NOT NULL,updated_time bigint NOT NULL,"
                    + "PRIMARY KEY(id),UNIQUE KEY uk_private_network_node(group_id,node_id),UNIQUE KEY uk_private_network_address(group_id,private_address),"
                    + "KEY idx_private_network_member_node(node_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS private_network_link ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,group_id bigint NOT NULL,source_node_id bigint NOT NULL,target_node_id bigint NOT NULL,"
                    + "source_address varchar(45) DEFAULT NULL,target_address varchar(45) NOT NULL,route_info varchar(500) DEFAULT NULL,"
                    + "interface_name varchar(64) DEFAULT NULL,state varchar(24) NOT NULL DEFAULT 'pending',latency_ms decimal(12,3) DEFAULT NULL,"
                    + "packet_loss decimal(9,5) DEFAULT NULL,last_error varchar(500) DEFAULT NULL,verified_at bigint DEFAULT NULL,updated_time bigint NOT NULL,"
                    + "PRIMARY KEY(id),UNIQUE KEY uk_private_network_link(group_id,source_node_id,target_node_id),"
                    + "KEY idx_private_network_link_state(group_id,state)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS network_route_application ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,name varchar(100) NOT NULL,tunnel_id bigint NOT NULL,entry_node_id bigint NOT NULL,"
                    + "exit_node_id bigint NOT NULL,proxy_type varchar(16) NOT NULL,bind_ip varchar(45) NOT NULL DEFAULT '',listen_port int NOT NULL,"
                    + "auth_username varchar(64) NOT NULL,auth_password text NOT NULL,service_name varchar(120) NOT NULL,chain_name varchar(120) NOT NULL,"
                    + "hop_ports varchar(500) NOT NULL,runtime_port int DEFAULT NULL,reality_server_name varchar(253) DEFAULT NULL,client_config text DEFAULT NULL,"
                    + "managed_tunnel tinyint NOT NULL DEFAULT 0,state varchar(24) NOT NULL DEFAULT 'provisioning',last_error varchar(500) DEFAULT NULL,"
                    + "last_test_at bigint DEFAULT NULL,last_test_latency_ms decimal(12,3) DEFAULT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,"
                    + "PRIMARY KEY(id),KEY idx_route_app_tunnel(tunnel_id,state),KEY idx_route_app_entry(entry_node_id,listen_port,state)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("tunnel", "hop_config", "longtext DEFAULT NULL AFTER node_path");
            ensureColumn("network_route_application", "managed_tunnel", "tinyint NOT NULL DEFAULT 0 AFTER hop_ports");
            ensureColumn("network_route_application", "runtime_port", "int DEFAULT NULL AFTER hop_ports");
            ensureColumn("network_route_application", "reality_server_name", "varchar(253) DEFAULT NULL AFTER runtime_port");
            ensureColumn("network_route_application", "client_config", "text DEFAULT NULL AFTER reality_server_name");
            ensureColumn("network_route_application", "xhttp_path", "varchar(255) DEFAULT NULL AFTER client_config");
            ensureColumn("network_route_application", "xhttp_mode", "varchar(16) DEFAULT NULL AFTER xhttp_path");
            ensureColumn("network_route_application", "xhttp_padding_bytes", "varchar(32) DEFAULT NULL AFTER xhttp_mode");
            ensureColumn("network_route_application", "xhttp_origin_domain", "varchar(253) DEFAULT NULL AFTER xhttp_padding_bytes");
            ensureColumn("network_route_application", "xhttp_upload_domain", "varchar(253) DEFAULT NULL AFTER xhttp_origin_domain");
            ensureColumn("network_route_application", "xhttp_download_domain", "varchar(253) DEFAULT NULL AFTER xhttp_upload_domain");
            ensureColumn("network_route_application", "aws_access_account_id", "bigint DEFAULT NULL AFTER xhttp_download_domain");
            ensureColumn("network_route_application", "dns_zone_id", "bigint DEFAULT NULL AFTER aws_access_account_id");
            ensureColumn("network_route_application", "xhttp_dns_record_id", "varchar(128) DEFAULT NULL AFTER dns_zone_id");
            ensureColumn("network_route_application", "xhttp_upload_distribution_id", "varchar(64) DEFAULT NULL AFTER xhttp_dns_record_id");
            ensureColumn("network_route_application", "xhttp_download_distribution_id", "varchar(64) DEFAULT NULL AFTER xhttp_upload_distribution_id");
            ensureColumn("network_route_application", "cloudfront_state", "varchar(24) DEFAULT NULL AFTER xhttp_download_distribution_id");
        } catch (DataAccessException e) {
            log.error("Private network storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }
}
