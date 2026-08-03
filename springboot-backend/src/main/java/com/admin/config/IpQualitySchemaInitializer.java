package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class IpQualitySchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public IpQualitySchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ip_quality_provider_setting (id bigint unsigned NOT NULL,ipqs_api_key text DEFAULT NULL,abuseipdb_api_key text DEFAULT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ip_quality_scan (id bigint unsigned NOT NULL AUTO_INCREMENT,node_id bigint NOT NULL,status varchar(16) NOT NULL,"
                    + "public_ipv4 varchar(64) DEFAULT NULL,public_ipv6 varchar(128) DEFAULT NULL,country_code varchar(8) DEFAULT NULL,country varchar(80) DEFAULT NULL,"
                    + "region varchar(120) DEFAULT NULL,city varchar(120) DEFAULT NULL,asn varchar(32) DEFAULT NULL,organization varchar(255) DEFAULT NULL,network_type varchar(32) DEFAULT NULL,"
                    + "risk_score int DEFAULT NULL,risk_level varchar(16) DEFAULT NULL,confidence varchar(16) DEFAULT NULL,risk_sources_json longtext,blacklist_json longtext,"
                    + "unlock_json longtext,dns_json longtext,ports_json longtext,error varchar(500) DEFAULT NULL,started_at bigint NOT NULL,finished_at bigint DEFAULT NULL,PRIMARY KEY(id),"
                    + "KEY idx_ip_quality_node(node_id,started_at),KEY idx_ip_quality_status(status,started_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.update("UPDATE ip_quality_scan SET status='failed',error='面板重启导致检测中断',finished_at=? WHERE status='running'", System.currentTimeMillis());
        } catch (DataAccessException e) { log.error("IP quality storage initialization failed", e); }
    }
}
