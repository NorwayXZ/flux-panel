-- CloudNest 2.65.0: forward advanced options (PROXY protocol, blocked client protocols, connection limit).
-- Run once only when maintaining the database manually. Normal panel startup is idempotent.

ALTER TABLE `forward`
  ADD COLUMN `proxy_protocol` tinyint NOT NULL DEFAULT 0 AFTER `interface_name`,
  ADD COLUMN `block_protocols` varchar(32) DEFAULT NULL AFTER `proxy_protocol`,
  ADD COLUMN `max_connections` int NOT NULL DEFAULT 0 AFTER `block_protocols`;
