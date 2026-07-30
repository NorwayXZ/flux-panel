CREATE TABLE IF NOT EXISTS service_telemetry_latest (
  node_id bigint NOT NULL,
  service_name varchar(140) NOT NULL,
  total_connections bigint NOT NULL DEFAULT 0,
  current_connections bigint NOT NULL DEFAULT 0,
  total_errors bigint NOT NULL DEFAULT 0,
  upload_speed bigint NOT NULL DEFAULT 0,
  download_speed bigint NOT NULL DEFAULT 0,
  interval_upload bigint NOT NULL DEFAULT 0,
  interval_download bigint NOT NULL DEFAULT 0,
  sampled_at bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (node_id, service_name),
  KEY idx_service_telemetry_name (service_name, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS service_traffic_daily (
  traffic_date date NOT NULL,
  node_id bigint NOT NULL,
  service_name varchar(140) NOT NULL,
  upload_bytes bigint NOT NULL DEFAULT 0,
  download_bytes bigint NOT NULL DEFAULT 0,
  updated_time bigint NOT NULL,
  PRIMARY KEY (traffic_date, node_id, service_name),
  KEY idx_service_daily_name (service_name, traffic_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS service_telemetry_sample (
  node_id bigint NOT NULL,
  service_name varchar(140) NOT NULL,
  sample_type varchar(16) NOT NULL,
  sample_value varchar(255) NOT NULL,
  source_kind varchar(24) NOT NULL DEFAULT '',
  seen_count bigint NOT NULL DEFAULT 0,
  last_seen bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (node_id, service_name, sample_type, sample_value, source_kind),
  KEY idx_service_sample_recent (service_name, sample_type, last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
