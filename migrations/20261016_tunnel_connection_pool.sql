-- CloudNest 2.66.0: TLS connection pool size per tunnel.
-- Run once only when maintaining the database manually. Normal panel startup is idempotent.

ALTER TABLE `tunnel`
  ADD COLUMN `pool_size` int NOT NULL DEFAULT 0 AFTER `protocol`;
