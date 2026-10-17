CREATE TABLE IF NOT EXISTS mysterium_event (
  id bigint NOT NULL AUTO_INCREMENT,
  node_id bigint NOT NULL,
  country char(2) NOT NULL,
  message varchar(500) NOT NULL,
  old_ip varchar(64) DEFAULT NULL,
  new_ip varchar(64) DEFAULT NULL,
  created_time bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_mysterium_event (node_id,country,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
