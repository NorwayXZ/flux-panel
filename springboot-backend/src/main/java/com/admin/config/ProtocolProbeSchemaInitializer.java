package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class ProtocolProbeSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public ProtocolProbeSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS protocol_probe_run ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,proxy_id bigint NOT NULL,user_id int NOT NULL,node_id bigint NOT NULL,"
                    + "proxy_type varchar(32) NOT NULL,probe_node_id bigint NOT NULL,status varchar(16) NOT NULL,"
                    + "available tinyint NOT NULL DEFAULT 0,target_url varchar(500) NOT NULL,download_bytes bigint NOT NULL DEFAULT 0,"
                    + "upload_bytes bigint NOT NULL DEFAULT 0,latency_ms decimal(12,3) DEFAULT NULL,handshake_ms decimal(12,3) DEFAULT NULL,"
                    + "download_bytes_actual bigint NOT NULL DEFAULT 0,download_mbps decimal(12,3) DEFAULT NULL,"
                    + "upload_bytes_actual bigint NOT NULL DEFAULT 0,upload_mbps decimal(12,3) DEFAULT NULL,"
                    + "download_status int DEFAULT NULL,upload_status int DEFAULT NULL,error varchar(500) DEFAULT NULL,"
                    + "agent_version varchar(64) DEFAULT NULL,started_at bigint NOT NULL,finished_at bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_protocol_probe_proxy(proxy_id,started_at),KEY idx_protocol_probe_user(user_id,started_at),"
                    + "KEY idx_protocol_probe_status(status,started_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("download_bytes_actual", "bigint NOT NULL DEFAULT 0 AFTER handshake_ms");
            ensureColumn("upload_bytes_actual", "bigint NOT NULL DEFAULT 0 AFTER download_mbps");
        } catch (DataAccessException e) {
            log.error("Protocol probe storage initialization failed", e);
        }
    }

    private void ensureColumn(String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name='protocol_probe_run' AND column_name=?",
                Integer.class, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE protocol_probe_run ADD COLUMN `" + column + "` " + definition);
        }
    }
}
