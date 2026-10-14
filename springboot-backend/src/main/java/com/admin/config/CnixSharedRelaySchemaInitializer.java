package com.admin.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class CnixSharedRelaySchemaInitializer {
    private final JdbcTemplate jdbc;

    public CnixSharedRelaySchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS cnix_shared_relay (tunnel_id bigint PRIMARY"
                    + " KEY,out_node_id bigint NOT NULL,service_port int NOT NULL,external_address"
                    + " varchar(255),cert_pem text NOT NULL,key_pem text NOT NULL,auth_seed"
                    + " varchar(128) NOT NULL,state varchar(32) NOT NULL DEFAULT"
                    + " 'mapping-required',last_error varchar(500),created_time bigint NOT"
                    + " NULL,UNIQUE KEY cnix_out_port(out_node_id,service_port)) ENGINE=InnoDB"
                    + " DEFAULT CHARSET=utf8mb4");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS cnix_shared_grant (tunnel_id bigint NOT NULL,forward_id"
                    + " bigint NOT NULL,username varchar(128) NOT NULL,password varchar(128) NOT"
                    + " NULL,targets text NOT NULL,enabled tinyint NOT NULL DEFAULT 1,PRIMARY"
                    + " KEY(tunnel_id,forward_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
