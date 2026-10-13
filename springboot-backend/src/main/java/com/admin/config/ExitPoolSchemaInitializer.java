package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 国家家宽出口的存储。所有改动都是新增的表和带默认值的列，
 * 旧版本后端读不到它们也能正常运行，因此版本可以直接回退。
 */
@Slf4j
@Component
public class ExitPoolSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public ExitPoolSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS exit_candidate ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, source varchar(24) NOT NULL, country varchar(8) NOT NULL, "
                    + "host varchar(64) NOT NULL, port int NOT NULL, proto varchar(8) NOT NULL, cipher varchar(32) NOT NULL, "
                    + "auth varchar(16) NOT NULL, ca text NOT NULL, cert text DEFAULT NULL, client_key text DEFAULT NULL, "
                    + "owner varchar(160) DEFAULT NULL, asn varchar(32) DEFAULT NULL, ip_kind varchar(16) NOT NULL DEFAULT 'unknown', "
                    + "flagged_proxy tinyint NOT NULL DEFAULT 0, list_score bigint NOT NULL DEFAULT 0, list_sessions int NOT NULL DEFAULT 0, "
                    + "list_speed bigint NOT NULL DEFAULT 0, list_uptime bigint NOT NULL DEFAULT 0, first_seen bigint NOT NULL, "
                    + "last_seen bigint NOT NULL, classified_at bigint DEFAULT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_exit_candidate (source,host,port,proto), KEY idx_exit_candidate_country (country,ip_kind,last_seen)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS exit_group ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, node_id bigint NOT NULL, country varchar(8) NOT NULL, "
                    + "status varchar(24) NOT NULL, detail varchar(500) DEFAULT NULL, active_member_id bigint DEFAULT NULL, "
                    + "active_exit_ip varchar(64) DEFAULT NULL, active_owner varchar(160) DEFAULT NULL, active_since bigint DEFAULT NULL, "
                    + "no_exit_since bigint DEFAULT NULL, runtime_version varchar(32) DEFAULT NULL, created_time bigint NOT NULL, "
                    + "updated_time bigint NOT NULL, PRIMARY KEY (id), UNIQUE KEY uk_exit_group (node_id,country)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS exit_member ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, group_id bigint NOT NULL, candidate_id bigint NOT NULL, "
                    + "state varchar(16) NOT NULL, exit_ip varchar(64) DEFAULT NULL, exit_owner varchar(160) DEFAULT NULL, "
                    + "exit_kind varchar(16) DEFAULT NULL, exit_country varchar(8) DEFAULT NULL, exit_flagged tinyint NOT NULL DEFAULT 0, "
                    + "approved tinyint NOT NULL DEFAULT 0, latency_ms int DEFAULT NULL, rx_bytes bigint NOT NULL DEFAULT 0, "
                    + "tx_bytes bigint NOT NULL DEFAULT 0, connected_at bigint DEFAULT NULL, last_ok_at bigint DEFAULT NULL, "
                    + "error varchar(300) DEFAULT NULL, retry_after bigint DEFAULT NULL, success_count int NOT NULL DEFAULT 0, "
                    + "fail_count int NOT NULL DEFAULT 0, attempt int NOT NULL DEFAULT 0, created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_exit_member (group_id,candidate_id), KEY idx_exit_member_state (group_id,state)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            SchemaSupport.ensureColumn(jdbcTemplate, "exit_member", "attempt", "int NOT NULL DEFAULT 0");
            // 深度检测得到的风险情报
            SchemaSupport.ensureColumn(jdbcTemplate, "exit_ip_info", "ipqs_score", "int DEFAULT NULL");
            SchemaSupport.ensureColumn(jdbcTemplate, "exit_ip_info", "abuse_score", "int DEFAULT NULL");
            SchemaSupport.ensureColumn(jdbcTemplate, "exit_ip_info", "reputation_json", "text DEFAULT NULL");
            SchemaSupport.ensureColumn(jdbcTemplate, "exit_ip_info", "reputation_at", "bigint DEFAULT NULL");
            // 手动指定的出口；它失效后清空，恢复自动选择
            SchemaSupport.ensureColumn(jdbcTemplate, "exit_group", "preferred_candidate_id", "bigint DEFAULT NULL");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS exit_event ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, group_id bigint NOT NULL, node_id bigint NOT NULL, country varchar(8) NOT NULL, "
                    + "type varchar(24) NOT NULL, from_ip varchar(64) DEFAULT NULL, to_ip varchar(64) DEFAULT NULL, "
                    + "message varchar(500) DEFAULT NULL, created_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "KEY idx_exit_event_group (group_id,created_time), KEY idx_exit_event_time (created_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS exit_ip_info ("
                    + "ip varchar(64) NOT NULL, country varchar(8) DEFAULT NULL, owner varchar(160) DEFAULT NULL, asn varchar(32) DEFAULT NULL, "
                    + "kind varchar(16) NOT NULL, flagged_proxy tinyint NOT NULL DEFAULT 0, checked_at bigint NOT NULL, PRIMARY KEY (ip)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("Exit pool storage initialization failed", e);
        }
    }
}
