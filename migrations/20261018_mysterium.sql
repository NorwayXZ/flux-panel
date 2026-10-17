-- Mysterium residential exits (2.83.0).
CREATE TABLE IF NOT EXISTS mysterium_setting (
  id int NOT NULL,
  enabled tinyint NOT NULL DEFAULT 0,
  terms_agreed_at bigint DEFAULT NULL,
  terms_agreed_by varchar(64) DEFAULT NULL,
  monthly_budget_usd decimal(12,2) NOT NULL DEFAULT 20,
  low_balance_myst decimal(20,6) NOT NULL DEFAULT 10,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mysterium_account (
  node_id bigint NOT NULL,
  identity varchar(64) DEFAULT NULL,
  registration varchar(32) DEFAULT NULL,
  channel_address varchar(64) DEFAULT NULL,
  balance decimal(20,6) NOT NULL DEFAULT 0,
  last_balance decimal(20,6) NOT NULL DEFAULT 0,
  month varchar(7) DEFAULT NULL,
  month_spent decimal(20,6) NOT NULL DEFAULT 0,
  suspended tinyint NOT NULL DEFAULT 0,
  last_status_at bigint DEFAULT NULL,
  last_error varchar(500) DEFAULT NULL,
  created_time bigint NOT NULL,
  PRIMARY KEY (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mysterium_connection (
  node_id bigint NOT NULL,
  country char(2) NOT NULL,
  proxy_port int NOT NULL,
  provider_id varchar(64) DEFAULT NULL,
  exit_ip varchar(64) DEFAULT NULL,
  city varchar(100) DEFAULT NULL,
  isp varchar(200) DEFAULT NULL,
  state varchar(16) NOT NULL DEFAULT 'connecting',
  fail_count int NOT NULL DEFAULT 0,
  excluded varchar(1000) DEFAULT NULL,
  latency_ms int DEFAULT NULL,
  last_check_at bigint DEFAULT NULL,
  last_ok_at bigint DEFAULT NULL,
  last_error varchar(500) DEFAULT NULL,
  bytes_received bigint NOT NULL DEFAULT 0,
  bytes_sent bigint NOT NULL DEFAULT 0,
  created_time bigint NOT NULL,
  PRIMARY KEY (node_id, country)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
