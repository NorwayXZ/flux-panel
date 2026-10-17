-- Cloudflare WARP accounts, one per node (2.82.0).
CREATE TABLE IF NOT EXISTS warp_account (
  node_id bigint NOT NULL,
  account_id varchar(64) DEFAULT NULL,
  private_key varchar(255) NOT NULL,
  public_key varchar(64) NOT NULL,
  address_v4 varchar(64) NOT NULL,
  address_v6 varchar(64) DEFAULT NULL,
  reserved varchar(32) NOT NULL,
  peer_public_key varchar(64) NOT NULL,
  peer_address varchar(64) NOT NULL,
  peer_port int NOT NULL,
  created_time bigint NOT NULL,
  last_test_at bigint DEFAULT NULL,
  last_test_ok tinyint DEFAULT NULL,
  last_test_ip varchar(64) DEFAULT NULL,
  last_test_country varchar(8) DEFAULT NULL,
  last_test_latency int DEFAULT NULL,
  last_error varchar(500) DEFAULT NULL,
  PRIMARY KEY (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
