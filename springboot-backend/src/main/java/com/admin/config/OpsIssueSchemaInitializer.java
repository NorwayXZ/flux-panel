package com.admin.config;

import javax.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OpsIssueSchemaInitializer {
    private final JdbcTemplate db;
    public OpsIssueSchemaInitializer(JdbcTemplate db) { this.db = db; }
    @PostConstruct public void initialize() {
        db.execute("CREATE TABLE IF NOT EXISTS ops_issue (id bigint unsigned NOT NULL AUTO_INCREMENT,"
                + "source_key varchar(64) NOT NULL,source_type varchar(24) NOT NULL,source_id bigint NOT NULL,"
                + "resource_type varchar(32) DEFAULT NULL,resource_id bigint DEFAULT NULL,title varchar(500) NOT NULL,evidence text,"
                + "phase varchar(32) NOT NULL DEFAULT 'detected',note text,chat_id bigint DEFAULT NULL,verify_run_id bigint DEFAULT NULL,"
                + "verify_started_at bigint DEFAULT NULL,verification text,created_at bigint NOT NULL,updated_at bigint NOT NULL,"
                + "PRIMARY KEY(id),UNIQUE KEY uk_ops_issue_source(source_key),KEY idx_ops_issue_phase(phase,updated_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
