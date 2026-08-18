-- CloudNest 2.51.31: Beijing-time preference windows for cross-entry failover.
CREATE TABLE IF NOT EXISTS cross_entry_failover_schedule (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    group_id bigint NOT NULL,
    days_mask int NOT NULL,
    start_minute int NOT NULL,
    end_minute int NOT NULL,
    preferred_forward_id bigint NOT NULL,
    enabled tinyint NOT NULL DEFAULT 1,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (id),
    KEY idx_cross_entry_schedule_group (group_id, enabled),
    KEY idx_cross_entry_schedule_target (group_id, preferred_forward_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
