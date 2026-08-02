-- CloudNest 2.44.1: TCP retransmission and UDP packet-loss measurements.
-- Run once only when maintaining the database manually. Normal panel startup is idempotent.

ALTER TABLE `bandwidth_test_task`
  ADD COLUMN `protocol` varchar(8) NOT NULL DEFAULT 'tcp' AFTER `listen_port`;

ALTER TABLE `bandwidth_test_run`
  ADD COLUMN `protocol` varchar(8) NOT NULL DEFAULT 'tcp' AFTER `target_node_id`,
  ADD COLUMN `rtt_ms` decimal(12,3) DEFAULT NULL AFTER `failed_streams`,
  ADD COLUMN `retransmits` bigint NOT NULL DEFAULT 0 AFTER `rtt_ms`,
  ADD COLUMN `retransmission_rate` decimal(9,5) NOT NULL DEFAULT 0 AFTER `retransmits`,
  ADD COLUMN `packets_sent` bigint NOT NULL DEFAULT 0 AFTER `retransmission_rate`,
  ADD COLUMN `packets_received` bigint NOT NULL DEFAULT 0 AFTER `packets_sent`,
  ADD COLUMN `packets_lost` bigint NOT NULL DEFAULT 0 AFTER `packets_received`,
  ADD COLUMN `packet_loss_percent` decimal(9,5) NOT NULL DEFAULT 0 AFTER `packets_lost`,
  ADD COLUMN `jitter_ms` decimal(12,3) NOT NULL DEFAULT 0 AFTER `packet_loss_percent`,
  ADD COLUMN `out_of_order_packets` bigint NOT NULL DEFAULT 0 AFTER `jitter_ms`;
