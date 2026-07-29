package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class AssetAndDynamicDnsSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public AssetAndDynamicDnsSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS server_asset ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,node_id bigint DEFAULT NULL,name varchar(120) NOT NULL,"
                    + "provider varchar(100) DEFAULT NULL,region varchar(100) DEFAULT NULL,cpu_spec varchar(100) DEFAULT NULL,"
                    + "memory_mb int DEFAULT NULL,disk_gb int DEFAULT NULL,bandwidth_mbps int DEFAULT NULL,"
                    + "currency varchar(8) NOT NULL DEFAULT 'CNY',monthly_cost decimal(12,2) NOT NULL DEFAULT 0,"
                    + "purchase_date bigint DEFAULT NULL,expiry_date bigint DEFAULT NULL,auto_renew tinyint NOT NULL DEFAULT 0,"
                    + "ipv4 varchar(64) DEFAULT NULL,ipv6 varchar(128) DEFAULT NULL,asn varchar(32) DEFAULT NULL,"
                    + "network_line varchar(160) DEFAULT NULL,traffic_plan varchar(160) DEFAULT NULL,tags varchar(500) DEFAULT NULL,"
                    + "notes text DEFAULT NULL,reminder_enabled tinyint NOT NULL DEFAULT 1,reminder_days varchar(64) NOT NULL DEFAULT '30,7,3,1,0',"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),UNIQUE KEY uk_server_asset_node(node_id),"
                    + "KEY idx_server_asset_expiry(expiry_date)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS dynamic_dns_provider ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,name varchar(100) NOT NULL,provider varchar(24) NOT NULL,"
                    + "credential_a varchar(2048) NOT NULL,credential_b varchar(2048) DEFAULT NULL,enabled tinyint NOT NULL DEFAULT 1,"
                    + "last_error varchar(500) DEFAULT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,"
                    + "PRIMARY KEY(id),UNIQUE KEY uk_dynamic_dns_provider(provider,name)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS dynamic_dns_rule ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,name varchar(100) NOT NULL,source_type varchar(16) NOT NULL DEFAULT 'node',"
                    + "node_id bigint DEFAULT NULL,connector_id bigint DEFAULT NULL,"
                    + "provider_source varchar(16) NOT NULL DEFAULT 'dynamic',provider_ref_id bigint NOT NULL,provider varchar(24) NOT NULL,"
                    + "zone_ref_id bigint DEFAULT NULL,zone_name varchar(253) NOT NULL,record_name varchar(253) NOT NULL,"
                    + "record_type varchar(8) NOT NULL,ttl int NOT NULL DEFAULT 600,check_interval_seconds int NOT NULL DEFAULT 60,"
                    + "enabled tinyint NOT NULL DEFAULT 1,last_detected_ip varchar(128) DEFAULT NULL,last_applied_ip varchar(128) DEFAULT NULL,"
                    + "provider_record_id varchar(128) DEFAULT NULL,last_status varchar(24) NOT NULL DEFAULT 'pending',"
                    + "last_error varchar(500) DEFAULT NULL,last_checked_at bigint DEFAULT NULL,last_updated_at bigint DEFAULT NULL,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "UNIQUE KEY uk_dynamic_dns_record(provider,zone_name,record_name,record_type),KEY idx_dynamic_dns_due(enabled,last_checked_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("dynamic_dns_rule", "source_type", "varchar(16) NOT NULL DEFAULT 'node' AFTER name");
            ensureColumn("dynamic_dns_rule", "connector_id", "bigint DEFAULT NULL AFTER node_id");
            ensureNullableColumn("dynamic_dns_rule", "node_id", "bigint DEFAULT NULL");
            ensureIndex("dynamic_dns_rule", "idx_dynamic_dns_source", "source_type,node_id,connector_id");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS dynamic_dns_history ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,rule_id bigint NOT NULL,old_ip varchar(128) DEFAULT NULL,"
                    + "new_ip varchar(128) DEFAULT NULL,status varchar(24) NOT NULL,error varchar(500) DEFAULT NULL,created_time bigint NOT NULL,"
                    + "PRIMARY KEY(id),KEY idx_dynamic_dns_history(rule_id,created_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("Asset and dynamic DNS storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }

    private void ensureNullableColumn(String table, String column, String definition) {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
        if (!"YES".equalsIgnoreCase(nullable)) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` " + definition);
        }
    }

    private void ensureIndex(String table, String index, String columns) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD KEY `" + index + "` (" + columns + ")");
        }
    }
}
