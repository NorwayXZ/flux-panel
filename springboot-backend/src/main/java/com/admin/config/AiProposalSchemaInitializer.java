package com.admin.config;

import javax.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Change proposals from the AI operations assistant: stored until an administrator decides. */
@Component
public class AiProposalSchemaInitializer {
  private final JdbcTemplate db;

  public AiProposalSchemaInitializer(JdbcTemplate db) {
    this.db = db;
  }

  @PostConstruct
  public void init() {
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_ops_proposal (id bigint PRIMARY KEY AUTO_INCREMENT,"
            + "creator_id bigint NOT NULL DEFAULT 0,source varchar(16) NOT NULL DEFAULT 'chat',"
            + "action varchar(32) NOT NULL,target_id bigint NOT NULL,target_name varchar(200),"
            + "title varchar(200) NOT NULL,reason varchar(1000) NOT NULL,"
            + "status varchar(16) NOT NULL DEFAULT 'pending',created_at bigint NOT NULL,"
            + "decided_by bigint,decided_at bigint,executed_at bigint,result varchar(1000),"
            + "KEY idx_ai_proposal_status(status,created_at),"
            + "KEY idx_ai_proposal_target(action,target_id,status))"
            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    db.update(
        "UPDATE ai_ops_proposal SET status='expired' WHERE status='pending' AND created_at<?",
        System.currentTimeMillis() - 7L * 86400000);
  }
}
