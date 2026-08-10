package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class UdpQuicDiagnosticSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public UdpQuicDiagnosticSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS udp_quic_diagnostic_task ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,name varchar(100) NOT NULL,source_node_id bigint NOT NULL,"
                    + "target_type varchar(16) NOT NULL DEFAULT 'node',target_node_id bigint DEFAULT NULL,target_host varchar(253) DEFAULT NULL,"
                    + "port int NOT NULL,mode varchar(16) NOT NULL DEFAULT 'udp_echo',server_name varchar(253) DEFAULT NULL,"
                    + "ip_family varchar(8) NOT NULL DEFAULT 'auto',sample_count int NOT NULL DEFAULT 5,timeout_ms int NOT NULL DEFAULT 3000,"
                    + "packet_size int NOT NULL DEFAULT 1200,idle_timeout_seconds int NOT NULL DEFAULT 15,alpn varchar(100) DEFAULT 'h3',"
                    + "verify_certificate tinyint NOT NULL DEFAULT 0,retention_days int NOT NULL DEFAULT 30,running tinyint NOT NULL DEFAULT 0,"
                    + "last_status varchar(16) NOT NULL DEFAULT 'pending',last_error varchar(500) DEFAULT NULL,last_run_at bigint DEFAULT NULL,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),KEY idx_udp_quic_source(source_node_id),"
                    + "KEY idx_udp_quic_target(target_node_id),KEY idx_udp_quic_status(running,last_status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS udp_quic_diagnostic_run ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,task_id bigint NOT NULL,status varchar(16) NOT NULL,source_node_id bigint NOT NULL,"
                    + "target_node_id bigint DEFAULT NULL,target_host varchar(253) NOT NULL,resolved_address varchar(128) DEFAULT NULL,"
                    + "ip_family varchar(8) NOT NULL,port int NOT NULL,mode varchar(16) NOT NULL,packet_size int NOT NULL DEFAULT 1200,"
                    + "sample_count int NOT NULL DEFAULT 0,success_count int NOT NULL DEFAULT 0,failure_rate decimal(7,3) NOT NULL DEFAULT 0,"
                    + "packet_loss_percent decimal(7,3) NOT NULL DEFAULT 0,rtt_min_ms decimal(12,3) DEFAULT NULL,rtt_avg_ms decimal(12,3) DEFAULT NULL,"
                    + "rtt_max_ms decimal(12,3) DEFAULT NULL,jitter_ms decimal(12,3) DEFAULT NULL,nat_idle_seconds int DEFAULT NULL,"
                    + "nat_idle_alive tinyint DEFAULT NULL,quic_handshake_avg_ms decimal(12,3) DEFAULT NULL,alpn varchar(100) DEFAULT NULL,"
                    + "diagnosis varchar(500) DEFAULT NULL,error varchar(500) DEFAULT NULL,samples_json mediumtext DEFAULT NULL,"
                    + "started_at bigint NOT NULL,finished_at bigint NOT NULL,PRIMARY KEY(id),KEY idx_udp_quic_run_task(task_id,started_at),"
                    + "KEY idx_udp_quic_run_time(started_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("udp_quic_diagnostic_task", "packet_size", "int NOT NULL DEFAULT 1200 AFTER timeout_ms");
            ensureColumn("udp_quic_diagnostic_task", "idle_timeout_seconds", "int NOT NULL DEFAULT 15 AFTER packet_size");
            ensureColumn("udp_quic_diagnostic_task", "alpn", "varchar(100) DEFAULT 'h3' AFTER idle_timeout_seconds");
            ensureColumn("udp_quic_diagnostic_task", "verify_certificate", "tinyint NOT NULL DEFAULT 0 AFTER alpn");
            ensureColumn("udp_quic_diagnostic_run", "diagnosis", "varchar(500) DEFAULT NULL AFTER alpn");
            ensureColumn("udp_quic_diagnostic_run", "samples_json", "mediumtext DEFAULT NULL AFTER error");
        } catch (DataAccessException e) {
            log.error("UDP / QUIC diagnostic storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", Integer.class, table, column);
        if (count == null || count == 0) jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
    }
}
