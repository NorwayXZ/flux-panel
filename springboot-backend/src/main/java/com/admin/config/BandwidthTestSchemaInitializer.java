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
                    + "listen_port int NOT NULL DEFAULT 5201,direction varchar(16) NOT NULL DEFAULT 'bidirectional',streams int NOT NULL DEFAULT 4,"
                    + "duration_seconds int NOT NULL DEFAULT 10,maximum_megabytes int NOT NULL DEFAULT 512,retention_days int NOT NULL DEFAULT 30,"
                    + "running tinyint NOT NULL DEFAULT 0,last_status varchar(16) NOT NULL DEFAULT 'pending',last_error varchar(500) DEFAULT NULL,"
                    + "last_run_at bigint DEFAULT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_bandwidth_source(source_node_id),KEY idx_bandwidth_target(target_node_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS bandwidth_test_run ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,task_id bigint NOT NULL,status varchar(16) NOT NULL,source_node_id bigint NOT NULL,"
                    + "target_node_id bigint NOT NULL,direction varchar(16) NOT NULL,streams int NOT NULL,duration_ms bigint NOT NULL DEFAULT 0,"
                    + "upload_bytes bigint NOT NULL DEFAULT 0,download_bytes bigint NOT NULL DEFAULT 0,upload_mbps decimal(12,3) NOT NULL DEFAULT 0,"
                    + "download_mbps decimal(12,3) NOT NULL DEFAULT 0,total_mbps decimal(12,3) NOT NULL DEFAULT 0,cpu_percent decimal(7,3) DEFAULT NULL,"
                    + "memory_used bigint DEFAULT NULL,memory_percent decimal(7,3) DEFAULT NULL,successful_streams int NOT NULL DEFAULT 0,"
                    + "failed_streams int NOT NULL DEFAULT 0,error varchar(500) DEFAULT NULL,started_at bigint NOT NULL,finished_at bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_bandwidth_run_task(task_id,started_at),KEY idx_bandwidth_run_time(started_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("Bandwidth test storage initialization failed", e);
        }
    }
}
