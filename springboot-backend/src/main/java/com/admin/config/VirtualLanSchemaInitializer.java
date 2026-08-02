package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class VirtualLanSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;
    public VirtualLanSchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS virtual_lan_network ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,name varchar(100) NOT NULL,cidr varchar(32) NOT NULL,hub_node_id bigint NOT NULL,"
                    + "listen_port int NOT NULL,state varchar(16) NOT NULL DEFAULT 'pending',last_error varchar(500) DEFAULT NULL,created_time bigint NOT NULL,"
                    + "updated_time bigint NOT NULL,PRIMARY KEY(id),UNIQUE KEY uk_virtual_lan_cidr(cidr),KEY idx_virtual_lan_hub(hub_node_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS virtual_lan_member ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,network_id bigint NOT NULL,target_type varchar(16) NOT NULL,target_id bigint NOT NULL,"
                    + "member_name varchar(120) NOT NULL,role varchar(16) NOT NULL,virtual_ip varchar(45) NOT NULL,public_key varchar(64) DEFAULT NULL,"
                    + "state varchar(16) NOT NULL DEFAULT 'pending',receive_bytes bigint NOT NULL DEFAULT 0,transmit_bytes bigint NOT NULL DEFAULT 0,"
                    + "latest_handshake bigint DEFAULT NULL,last_error varchar(500) DEFAULT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "UNIQUE KEY uk_virtual_lan_target(network_id,target_type,target_id),UNIQUE KEY uk_virtual_lan_ip(network_id,virtual_ip),"
                    + "KEY idx_virtual_lan_member(network_id,state)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) { log.error("Virtual LAN storage initialization failed", e); }
    }
}
