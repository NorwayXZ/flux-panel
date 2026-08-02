package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class NftForwardSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public NftForwardSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS nft_forward_rule ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,user_id int NOT NULL,name varchar(100) NOT NULL,"
                    + "node_id bigint NOT NULL,listen_address varchar(45) NOT NULL DEFAULT '0.0.0.0',listen_port int NOT NULL,"
                    + "protocol varchar(16) NOT NULL,target_address varchar(45) NOT NULL,target_port int NOT NULL,"
                    + "nat_mode varchar(24) NOT NULL DEFAULT 'masquerade',source_cidrs text DEFAULT NULL,enabled tinyint NOT NULL DEFAULT 1,"
                    + "state varchar(24) NOT NULL DEFAULT 'provisioning',generation bigint NOT NULL DEFAULT 0,applied_hash varchar(64) DEFAULT NULL,"
                    + "packet_count bigint NOT NULL DEFAULT 0,byte_count bigint NOT NULL DEFAULT 0,last_error varchar(500) DEFAULT NULL,"
                    + "last_warning varchar(500) DEFAULT NULL,last_good_config longtext DEFAULT NULL,last_synced_at bigint DEFAULT NULL,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_nft_forward_node(node_id,state),KEY idx_nft_forward_listener(node_id,listen_port,protocol)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS nft_forward_event ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,rule_id bigint NOT NULL,node_id bigint NOT NULL,"
                    + "event_type varchar(32) NOT NULL,status varchar(24) NOT NULL,detail varchar(500) DEFAULT NULL,"
                    + "created_time bigint NOT NULL,PRIMARY KEY(id),KEY idx_nft_forward_event(rule_id,created_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("nftables forwarding storage initialization failed", e);
        }
    }
}
