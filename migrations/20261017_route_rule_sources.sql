-- Rule-set subscriptions, downloaded GEOSITE / GEOIP lists and exit groups (2.79.0).
CREATE TABLE IF NOT EXISTS route_list_cache (
  list_key varchar(100) NOT NULL,
  data mediumtext,
  entry_count int NOT NULL DEFAULT 0,
  updated_at bigint NOT NULL,
  checked_at bigint NOT NULL,
  last_error varchar(500) DEFAULT NULL,
  PRIMARY KEY (list_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS route_ruleset (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id int NOT NULL,
  name varchar(100) NOT NULL,
  url varchar(1000) NOT NULL,
  behavior varchar(16) NOT NULL,
  interval_hours int NOT NULL DEFAULT 72,
  data mediumtext,
  entry_count int NOT NULL DEFAULT 0,
  skipped int NOT NULL DEFAULT 0,
  updated_at bigint DEFAULT NULL,
  checked_at bigint DEFAULT NULL,
  last_error varchar(500) DEFAULT NULL,
  created_time bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_route_ruleset_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Members of an exit group, e.g. ",3,7,". Skip if the column already exists.
ALTER TABLE proxy_exit ADD COLUMN members varchar(1000) DEFAULT NULL;

-- Imported Clash rules can exceed a text column.
ALTER TABLE private_proxy MODIFY COLUMN route_rules mediumtext DEFAULT NULL;
