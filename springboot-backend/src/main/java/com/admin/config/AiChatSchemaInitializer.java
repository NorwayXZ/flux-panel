package com.admin.config;

import javax.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AiChatSchemaInitializer {
  private final JdbcTemplate db;

  public AiChatSchemaInitializer(JdbcTemplate db) {
    this.db = db;
  }

  @PostConstruct
  public void initialize() {
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_ops_chat (id bigint PRIMARY KEY AUTO_INCREMENT,owner_id"
            + " bigint NOT NULL,title varchar(100) NOT NULL,created_at bigint NOT NULL,updated_at"
            + " bigint NOT NULL,KEY idx_ai_chat_owner(owner_id,updated_at)) ENGINE=InnoDB DEFAULT"
            + " CHARSET=utf8mb4");
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_ops_chat_turn (id bigint PRIMARY KEY AUTO_INCREMENT,chat_id"
            + " bigint NOT NULL,user_content text NOT NULL,policy_json text NOT NULL,status"
            + " varchar(24) NOT NULL,events mediumtext,created_at bigint NOT NULL,finished_at"
            + " bigint,KEY idx_ai_turn(chat_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    db.update(
        "UPDATE ai_ops_chat_turn SET status='interrupted',finished_at=? WHERE status='running'",
        System.currentTimeMillis());
  }
}
