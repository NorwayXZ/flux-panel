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
                    + "rule_type varchar(24) NOT NULL DEFAULT 'carrier',rule_name varchar(100) DEFAULT NULL,priority int NOT NULL DEFAULT 100,"
                    + "backend_forward_id bigint NOT NULL,cidrs longtext NOT NULL,region varchar(100) DEFAULT NULL,asn varchar(64) DEFAULT NULL,"
                    + "tags varchar(255) DEFAULT NULL,quality_policy varchar(24) NOT NULL DEFAULT 'static',notes varchar(500) DEFAULT NULL,"
                    + "enabled tinyint NOT NULL DEFAULT 1,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_source_ip_entry_carrier(group_id,carrier),KEY idx_source_ip_entry_route_group(group_id),"
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
            ensureColumn("source_ip_entry_route", "rule_type", "varchar(24) NOT NULL DEFAULT 'carrier' AFTER carrier");
            ensureColumn("source_ip_entry_route", "rule_name", "varchar(100) DEFAULT NULL AFTER rule_type");
            ensureColumn("source_ip_entry_route", "priority", "int NOT NULL DEFAULT 100 AFTER rule_name");
            ensureColumn("source_ip_entry_route", "region", "varchar(100) DEFAULT NULL AFTER cidrs");
            ensureColumn("source_ip_entry_route", "asn", "varchar(64) DEFAULT NULL AFTER region");
            ensureColumn("source_ip_entry_route", "tags", "varchar(255) DEFAULT NULL AFTER asn");
            ensureColumn("source_ip_entry_route", "quality_policy", "varchar(24) NOT NULL DEFAULT 'static' AFTER tags");
            ensureColumn("source_ip_entry_route", "notes", "varchar(500) DEFAULT NULL AFTER quality_policy");
            dropIndexIfExists("source_ip_entry_route", "uk_source_ip_entry_carrier");
            ensureIndex("source_ip_entry_route", "idx_source_ip_entry_carrier", "(`group_id`,`carrier`)");
            normalizeLegacyRouteTypes();
        } catch (DataAccessException e) {
            log.error("Source IP entry storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (count == null || count == 0) jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
    }

    private void dropIndexIfExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",
                Integer.class, table, index);
        if (count != null && count > 0) jdbcTemplate.execute("ALTER TABLE `" + table + "` DROP INDEX `" + index + "`");
    }

    private void ensureIndex(String table, String index, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",
                Integer.class, table, index);
        if (count == null || count == 0) jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD INDEX `" + index + "` " + definition);
    }

    private void normalizeLegacyRouteTypes() {
        jdbcTemplate.update("UPDATE source_ip_entry_route SET rule_type='default' WHERE carrier='default' AND rule_type='carrier'");
        jdbcTemplate.update("UPDATE source_ip_entry_route SET rule_type='cidr' WHERE carrier='custom' AND rule_type='carrier'");
    }
}
