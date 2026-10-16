-- Refreshed domain lists for the built-in routing groups (2.78.0).
CREATE TABLE IF NOT EXISTS route_preset_data (
  preset_key varchar(32) NOT NULL,
  domain_suffix mediumtext,
  domain mediumtext,
  domain_keyword text,
  updated_at bigint NOT NULL,
  checked_at bigint NOT NULL,
  last_error varchar(500) DEFAULT NULL,
  PRIMARY KEY (preset_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
