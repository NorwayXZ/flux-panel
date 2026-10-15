-- CloudNest 2.73.0: hourly traffic split into inbound and outbound, kept for eight days.
-- Run once only when maintaining the database manually. Normal panel startup is idempotent.

ALTER TABLE `statistics_flow`
  ADD COLUMN `in_flow` bigint NOT NULL DEFAULT 0 AFTER `flow`,
  ADD COLUMN `out_flow` bigint NOT NULL DEFAULT 0 AFTER `in_flow`,
  ADD COLUMN `total_in_flow` bigint DEFAULT NULL AFTER `total_flow`,
  ADD COLUMN `total_out_flow` bigint DEFAULT NULL AFTER `total_in_flow`,
  ADD KEY `idx_statistics_flow_created` (`created_time`);
