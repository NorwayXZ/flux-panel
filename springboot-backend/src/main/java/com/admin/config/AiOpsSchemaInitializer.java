package com.admin.config;

import javax.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AiOpsSchemaInitializer {
  private final JdbcTemplate db;

  public AiOpsSchemaInitializer(JdbcTemplate db) {
    this.db = db;
  }

  @PostConstruct
  public void init() {
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_ops_settings (id int PRIMARY KEY,base_url varchar(500) NOT"
            + " NULL,model varchar(200) NOT NULL,encrypted_key text) ENGINE=InnoDB DEFAULT"
            + " CHARSET=utf8mb4");
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_ops_task (id bigint PRIMARY KEY AUTO_INCREMENT,config_json"
            + " mediumtext NOT NULL,enabled tinyint NOT NULL DEFAULT 0,next_run bigint NOT"
            + " NULL,running tinyint NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_ops_run (id bigint PRIMARY KEY AUTO_INCREMENT,task_id bigint"
            + " NOT NULL,started_at bigint NOT NULL,finished_at bigint,status varchar(32) NOT"
            + " NULL,detail mediumtext,KEY idx_ai_run(task_id,started_at)) ENGINE=InnoDB DEFAULT"
            + " CHARSET=utf8mb4");
    db.update(
        "UPDATE ai_ops_run SET status='interrupted',finished_at=? WHERE status='running'",
        System.currentTimeMillis());
    db.update("UPDATE ai_ops_task SET running=0 WHERE running=1");
  }
}
