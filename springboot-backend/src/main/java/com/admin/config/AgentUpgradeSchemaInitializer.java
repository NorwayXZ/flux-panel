package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class AgentUpgradeSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public AgentUpgradeSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agent_upgrade_task ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, task_id varchar(64) NOT NULL, "
                    + "node_id bigint NOT NULL, node_name varchar(160) NOT NULL, "
                    + "from_version varchar(32) DEFAULT NULL, target_version varchar(32) NOT NULL, "
                    + "state varchar(32) NOT NULL, message varchar(255) DEFAULT NULL, "
                    + "requested_by int NOT NULL, requested_at bigint NOT NULL, "
                    + "updated_at bigint NOT NULL, finished_at bigint DEFAULT NULL, "
                    + "PRIMARY KEY (id), UNIQUE KEY uk_agent_upgrade_task (task_id), "
                    + "KEY idx_agent_upgrade_node_time (node_id, requested_at), "
                    + "KEY idx_agent_upgrade_state_time (state, updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("agent_upgrade_task", "batch_id", "varchar(64) DEFAULT NULL AFTER task_id");
            ensureColumn("agent_upgrade_task", "sequence_no", "int DEFAULT NULL AFTER batch_id");
            ensureIndex("agent_upgrade_task", "idx_agent_upgrade_batch", "batch_id, sequence_no");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agent_upgrade_batch ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,batch_id varchar(64) NOT NULL,target_version varchar(32) NOT NULL,"
                    + "state varchar(24) NOT NULL,mode varchar(16) NOT NULL DEFAULT 'staged',node_ids text NOT NULL,total_nodes int NOT NULL,completed_nodes int NOT NULL DEFAULT 0,"
                    + "current_node_id bigint DEFAULT NULL,current_node_name varchar(160) DEFAULT NULL,message varchar(500) DEFAULT NULL,"
                    + "requested_by int NOT NULL,started_at bigint NOT NULL,updated_at bigint NOT NULL,finished_at bigint DEFAULT NULL,"
                    + "PRIMARY KEY(id),UNIQUE KEY uk_agent_upgrade_batch(batch_id),KEY idx_agent_upgrade_batch_state(state,updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("agent_upgrade_batch", "mode", "varchar(16) NOT NULL DEFAULT 'staged' AFTER state");
        } catch (DataAccessException e) {
            log.error("Agent upgrade storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        SchemaSupport.ensureColumn(jdbcTemplate, table, column, definition);
    }

    private void ensureIndex(String table, String index, String columns) {
        SchemaSupport.ensureIndex(jdbcTemplate, table, index, columns);
    }
}
