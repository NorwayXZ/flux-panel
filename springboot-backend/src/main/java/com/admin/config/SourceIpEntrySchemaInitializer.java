package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/** Keeps new installations and upgrades on the same source-IP routing schema. */
@Slf4j
@Component
public class SourceIpEntrySchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public SourceIpEntrySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS source_ip_entry_group ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,user_id int NOT NULL,name varchar(100) NOT NULL,"
                    + "ingress_node_id bigint NOT NULL,listen_host varchar(128) NOT NULL DEFAULT '',listen_port int NOT NULL,"
                    + "default_route_id bigint DEFAULT NULL,enabled tinyint NOT NULL DEFAULT 1,state varchar(24) NOT NULL DEFAULT 'provisioning',"
                    + "last_error varchar(500) DEFAULT NULL,last_synced_at bigint DEFAULT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,"
                    + "PRIMARY KEY(id),UNIQUE KEY uk_source_ip_entry_listener(ingress_node_id,listen_port),"
                    + "KEY idx_source_ip_entry_user(user_id,state),KEY idx_source_ip_entry_node(ingress_node_id,state)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS source_ip_entry_route ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,group_id bigint NOT NULL,carrier varchar(24) NOT NULL,"
                    + "backend_forward_id bigint NOT NULL,cidrs longtext NOT NULL,enabled tinyint NOT NULL DEFAULT 1,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "UNIQUE KEY uk_source_ip_entry_carrier(group_id,carrier),KEY idx_source_ip_entry_route_group(group_id),"
                    + "KEY idx_source_ip_entry_route_forward(backend_forward_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS source_ip_entry_event ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,group_id bigint NOT NULL,event_type varchar(32) NOT NULL,"
                    + "status varchar(24) NOT NULL,detail varchar(500) DEFAULT NULL,created_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_source_ip_entry_event(group_id,created_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS source_ip_carrier_database ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,carrier varchar(24) NOT NULL,cidrs longtext NOT NULL,"
                    + "ipv4_count int NOT NULL DEFAULT 0,ipv6_count int NOT NULL DEFAULT 0,cidr_count int NOT NULL DEFAULT 0,"
                    + "source_urls text,last_error varchar(500) DEFAULT NULL,state varchar(24) NOT NULL DEFAULT 'pending',"
                    + "updated_time bigint NOT NULL,PRIMARY KEY(id),UNIQUE KEY uk_source_ip_carrier(carrier),KEY idx_source_ip_carrier_state(state)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("Source IP entry storage initialization failed", e);
        }
    }
}
