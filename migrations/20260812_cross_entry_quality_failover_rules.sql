-- CloudNest 2.50.0: cross-entry quality failover hardening.
-- Normal panel startup is idempotent; this file is for manual database maintenance.

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='routing_mode');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `routing_mode` varchar(24) NOT NULL DEFAULT ''failover'' AFTER `auto_failback`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_enabled` tinyint NOT NULL DEFAULT 0 AFTER `routing_mode`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_probe_source_type');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_probe_source_type` varchar(16) NOT NULL DEFAULT ''panel'' AFTER `quality_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_probe_source_id');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_probe_source_id` bigint DEFAULT NULL AFTER `quality_probe_source_type`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_probe_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_probe_count` int NOT NULL DEFAULT 4 AFTER `quality_probe_source_id`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_degrade_threshold_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_degrade_threshold_ms` int NOT NULL DEFAULT 100 AFTER `quality_probe_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_recover_threshold_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_recover_threshold_ms` int NOT NULL DEFAULT 60 AFTER `quality_degrade_threshold_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_degrade_factor');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_degrade_factor` decimal(8,2) NOT NULL DEFAULT 3.00 AFTER `quality_recover_threshold_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_recover_factor');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_recover_factor` decimal(8,2) NOT NULL DEFAULT 1.80 AFTER `quality_degrade_factor`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_degrade_samples');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_degrade_samples` int NOT NULL DEFAULT 3 AFTER `quality_recover_factor`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_recover_samples');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_recover_samples` int NOT NULL DEFAULT 3 AFTER `quality_degrade_samples`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_loss_threshold_percent');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_loss_threshold_percent` decimal(8,2) NOT NULL DEFAULT 30.00 AFTER `quality_recover_samples`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_p95_threshold_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_p95_threshold_ms` int NOT NULL DEFAULT 100 AFTER `quality_loss_threshold_percent`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_jitter_threshold_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_jitter_threshold_ms` int NOT NULL DEFAULT 50 AFTER `quality_p95_threshold_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_fixed_target_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_fixed_target_enabled` tinyint NOT NULL DEFAULT 0 AFTER `quality_jitter_threshold_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_fixed_target_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_fixed_target_ms` int NOT NULL DEFAULT 20 AFTER `quality_fixed_target_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_fixed_target_strict');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_fixed_target_strict` tinyint NOT NULL DEFAULT 1 AFTER `quality_fixed_target_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_flap_guard_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_flap_guard_enabled` tinyint NOT NULL DEFAULT 1 AFTER `quality_fixed_target_strict`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_flap_window_seconds');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_flap_window_seconds` int NOT NULL DEFAULT 900 AFTER `quality_flap_guard_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_flap_threshold');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_flap_threshold` int NOT NULL DEFAULT 3 AFTER `quality_flap_window_seconds`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_flap_suppress_seconds');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_flap_suppress_seconds` int NOT NULL DEFAULT 1800 AFTER `quality_flap_threshold`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='smart_selection_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `smart_selection_enabled` tinyint NOT NULL DEFAULT 1 AFTER `quality_flap_suppress_seconds`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='degraded_fallback_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `degraded_fallback_enabled` tinyint NOT NULL DEFAULT 1 AFTER `smart_selection_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='same_fault_avoidance_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `same_fault_avoidance_enabled` tinyint NOT NULL DEFAULT 1 AFTER `degraded_fallback_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='topology_avoidance_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `topology_avoidance_enabled` tinyint NOT NULL DEFAULT 1 AFTER `same_fault_avoidance_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='min_residency_seconds');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `min_residency_seconds` int NOT NULL DEFAULT 300 AFTER `topology_avoidance_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='failback_gain_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `failback_gain_ms` int NOT NULL DEFAULT 5 AFTER `min_residency_seconds`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='failback_gain_percent');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `failback_gain_percent` decimal(8,2) NOT NULL DEFAULT 15.00 AFTER `failback_gain_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='preheat_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `preheat_enabled` tinyint NOT NULL DEFAULT 1 AFTER `failback_gain_percent`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='preheat_backup_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `preheat_backup_count` int NOT NULL DEFAULT 3 AFTER `preheat_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='preheat_strict_isolation');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `preheat_strict_isolation` tinyint NOT NULL DEFAULT 1 AFTER `preheat_backup_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='post_switch_verify_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `post_switch_verify_enabled` tinyint NOT NULL DEFAULT 1 AFTER `preheat_strict_isolation`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='dns_verify_enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `dns_verify_enabled` tinyint NOT NULL DEFAULT 1 AFTER `post_switch_verify_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='manual_control_mode');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `manual_control_mode` varchar(16) NOT NULL DEFAULT ''auto'' AFTER `dns_verify_enabled`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='locked_member_id');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `locked_member_id` bigint DEFAULT NULL AFTER `manual_control_mode`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_probe_status');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_probe_status` varchar(24) NOT NULL DEFAULT ''disabled'' AFTER `locked_member_id`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_probe_error');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_probe_error` varchar(500) DEFAULT NULL AFTER `quality_probe_status`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE `cross_entry_failover_group`
SET `failback_gain_ms`=5, `failback_gain_percent`=15.00
WHERE `failback_gain_ms`=10 AND `failback_gain_percent`=20.00;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_group' AND column_name='quality_probe_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_group` ADD COLUMN `quality_probe_at` bigint DEFAULT NULL AFTER `quality_probe_error`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='weight');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `weight` int NOT NULL DEFAULT 100 AFTER `priority`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='enabled');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `enabled` tinyint NOT NULL DEFAULT 1 AFTER `weight`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_latency_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_latency_ms` int DEFAULT NULL AFTER `latency_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_p95_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_p95_ms` int DEFAULT NULL AFTER `quality_latency_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_jitter_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_jitter_ms` int DEFAULT NULL AFTER `quality_p95_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_loss_percent');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_loss_percent` decimal(8,2) DEFAULT NULL AFTER `quality_jitter_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_baseline_ms');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_baseline_ms` int DEFAULT NULL AFTER `quality_loss_percent`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_preheated');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_preheated` tinyint NOT NULL DEFAULT 0 AFTER `quality_baseline_ms`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='topology_signature');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `topology_signature` varchar(500) DEFAULT NULL AFTER `entry_address`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_state');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_state` varchar(24) NOT NULL DEFAULT ''unknown'' AFTER `quality_preheated`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_bad_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_bad_count` int NOT NULL DEFAULT 0 AFTER `quality_state`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_good_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_good_count` int NOT NULL DEFAULT 0 AFTER `quality_bad_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_flap_count');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_flap_count` int NOT NULL DEFAULT 0 AFTER `quality_good_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_flap_window_started_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_flap_window_started_at` bigint DEFAULT NULL AFTER `quality_flap_count`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_suppressed_until');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_suppressed_until` bigint DEFAULT NULL AFTER `quality_flap_window_started_at`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_suppressed_reason');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_suppressed_reason` varchar(255) DEFAULT NULL AFTER `quality_suppressed_until`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_last_error');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_last_error` varchar(500) DEFAULT NULL AFTER `quality_suppressed_reason`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='quality_checked_at');
SET @sql := IF(@col_exists=0, 'ALTER TABLE `cross_entry_failover_member` ADD COLUMN `quality_checked_at` bigint DEFAULT NULL AFTER `quality_last_error`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `cross_entry_dns_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `group_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `provider_record_id` varchar(64) NOT NULL,
  `content` varchar(255) NOT NULL,
  `created_time` bigint NOT NULL,
  `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cross_entry_dns_member` (`group_id`,`member_id`),
  UNIQUE KEY `uk_cross_entry_dns_provider` (`provider_record_id`),
  KEY `idx_cross_entry_dns_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
