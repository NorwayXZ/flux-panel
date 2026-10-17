-- Shared payment control plane; traffic tunnels remain local to each node.
CREATE TABLE IF NOT EXISTS mysterium_shared_pool (
  id int PRIMARY KEY,
  owner_node_id bigint NOT NULL,
  consumer varchar(64) NOT NULL,
  endpoint varchar(1000) NOT NULL,
  enabled tinyint NOT NULL DEFAULT 0,
  created_time bigint NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mysterium_shared_member (
  node_id bigint PRIMARY KEY,
  token_hash varchar(64) NOT NULL,
  state varchar(20) NOT NULL,
  last_error varchar(500) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
