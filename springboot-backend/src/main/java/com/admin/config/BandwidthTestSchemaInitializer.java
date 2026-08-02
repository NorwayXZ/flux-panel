package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class BandwidthTestSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public BandwidthTestSchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS bandwidth_test_task ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,name varchar(100) NOT NULL,source_node_id bigint NOT NULL,target_node_id bigint NOT NULL,"
                    + "listen_port int NOT NULL DEFAULT 5201,protocol varchar(8) NOT NULL DEFAULT 'tcp',direction varchar(16) NOT NULL DEFAULT 'bidirectional',streams int NOT NULL DEFAULT 4,"
                    + "duration_seconds int NOT NULL DEFAULT 10,maximum_megabytes int NOT NULL DEFAULT 512,retention_days int NOT NULL DEFAULT 30,"
                    + "running tinyint NOT NULL DEFAULT 0,last_status varchar(16) NOT NULL DEFAULT 'pending',last_error varchar(500) DEFAULT NULL,"
                    + "last_run_at bigint DEFAULT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_bandwidth_source(source_node_id),KEY idx_bandwidth_target(target_node_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS bandwidth_test_run ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,task_id bigint NOT NULL,status varchar(16) NOT NULL,source_node_id bigint NOT NULL,"
                    + "target_node_id bigint NOT NULL,protocol varchar(8) NOT NULL DEFAULT 'tcp',direction varchar(16) NOT NULL,streams int NOT NULL,duration_ms bigint NOT NULL DEFAULT 0,"
                    + "upload_bytes bigint NOT NULL DEFAULT 0,download_bytes bigint NOT NULL DEFAULT 0,upload_mbps decimal(12,3) NOT NULL DEFAULT 0,"
                    + "download_mbps decimal(12,3) NOT NULL DEFAULT 0,total_mbps decimal(12,3) NOT NULL DEFAULT 0,cpu_percent decimal(7,3) DEFAULT NULL,"
                    + "memory_used bigint DEFAULT NULL,memory_percent decimal(7,3) DEFAULT NULL,successful_streams int NOT NULL DEFAULT 0,"
                    + "failed_streams int NOT NULL DEFAULT 0,rtt_ms decimal(12,3) DEFAULT NULL,retransmits bigint NOT NULL DEFAULT 0,"
                    + "retransmission_rate decimal(9,5) NOT NULL DEFAULT 0,packets_sent bigint NOT NULL DEFAULT 0,packets_received bigint NOT NULL DEFAULT 0,"
                    + "packets_lost bigint NOT NULL DEFAULT 0,packet_loss_percent decimal(9,5) NOT NULL DEFAULT 0,jitter_ms decimal(12,3) NOT NULL DEFAULT 0,"
                    + "out_of_order_packets bigint NOT NULL DEFAULT 0,error varchar(500) DEFAULT NULL,started_at bigint NOT NULL,finished_at bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_bandwidth_run_task(task_id,started_at),KEY idx_bandwidth_run_time(started_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("bandwidth_test_task", "protocol", "varchar(8) NOT NULL DEFAULT 'tcp' AFTER listen_port");
            ensureColumn("bandwidth_test_run", "protocol", "varchar(8) NOT NULL DEFAULT 'tcp' AFTER target_node_id");
            ensureColumn("bandwidth_test_run", "rtt_ms", "decimal(12,3) DEFAULT NULL AFTER failed_streams");
            ensureColumn("bandwidth_test_run", "retransmits", "bigint NOT NULL DEFAULT 0 AFTER rtt_ms");
            ensureColumn("bandwidth_test_run", "retransmission_rate", "decimal(9,5) NOT NULL DEFAULT 0 AFTER retransmits");
            ensureColumn("bandwidth_test_run", "packets_sent", "bigint NOT NULL DEFAULT 0 AFTER retransmission_rate");
            ensureColumn("bandwidth_test_run", "packets_received", "bigint NOT NULL DEFAULT 0 AFTER packets_sent");
            ensureColumn("bandwidth_test_run", "packets_lost", "bigint NOT NULL DEFAULT 0 AFTER packets_received");
            ensureColumn("bandwidth_test_run", "packet_loss_percent", "decimal(9,5) NOT NULL DEFAULT 0 AFTER packets_lost");
            ensureColumn("bandwidth_test_run", "jitter_ms", "decimal(12,3) NOT NULL DEFAULT 0 AFTER packet_loss_percent");
            ensureColumn("bandwidth_test_run", "out_of_order_packets", "bigint NOT NULL DEFAULT 0 AFTER jitter_ms");
        } catch (DataAccessException e) {
            log.error("Bandwidth test storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", Integer.class, table, column);
        if (count == null || count == 0) jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
    }
}
