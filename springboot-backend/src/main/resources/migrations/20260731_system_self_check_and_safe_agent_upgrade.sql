-- Applied automatically by SystemSelfCheckSchemaInitializer and
-- AgentUpgradeSchemaInitializer. This copy documents the release schema.
CREATE TABLE IF NOT EXISTS system_self_check_run (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  status varchar(24) NOT NULL,
  scope_node_id bigint DEFAULT NULL,
  scope_type varchar(24) DEFAULT NULL,
  scope_resource_id bigint DEFAULT NULL,
  total_checks int NOT NULL DEFAULT 0,
  healthy_count int NOT NULL DEFAULT 0,
  warning_count int NOT NULL DEFAULT 0,
  failed_count int NOT NULL DEFAULT 0,
  skipped_count int NOT NULL DEFAULT 0,
  message varchar(500) DEFAULT NULL,
  requested_by int NOT NULL,
  started_at bigint NOT NULL,
  finished_at bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_self_check_started (started_at),
  KEY idx_self_check_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_self_check_finding (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  run_id bigint NOT NULL,
  category varchar(32) NOT NULL,
  resource_type varchar(32) NOT NULL,
  resource_id bigint DEFAULT NULL,
  resource_name varchar(253) DEFAULT NULL,
  status varchar(16) NOT NULL,
  fault_segment varchar(120) NOT NULL,
  summary varchar(500) NOT NULL,
  evidence text DEFAULT NULL,
  impact varchar(500) DEFAULT NULL,
  remediation text DEFAULT NULL,
  sort_order int NOT NULL DEFAULT 0,
  created_at bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_self_check_finding_run (run_id, status, sort_order),
  KEY idx_self_check_finding_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_identity_baseline (
  node_id bigint NOT NULL,
  machine_fingerprint varchar(64) NOT NULL,
  hostname varchar(253) DEFAULT NULL,
  first_seen_at bigint NOT NULL,
  last_seen_at bigint NOT NULL,
  PRIMARY KEY (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_upgrade_batch (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  batch_id varchar(64) NOT NULL,
  target_version varchar(32) NOT NULL,
  state varchar(24) NOT NULL,
  mode varchar(16) NOT NULL DEFAULT 'staged',
  node_ids text NOT NULL,
  total_nodes int NOT NULL,
  completed_nodes int NOT NULL DEFAULT 0,
  current_node_id bigint DEFAULT NULL,
  current_node_name varchar(160) DEFAULT NULL,
  message varchar(500) DEFAULT NULL,
  requested_by int NOT NULL,
  started_at bigint NOT NULL,
  updated_at bigint NOT NULL,
  finished_at bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_upgrade_batch (batch_id),
  KEY idx_agent_upgrade_batch_state (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
