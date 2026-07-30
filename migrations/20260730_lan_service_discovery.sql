ALTER TABLE internal_connector
    ADD COLUMN discovery_enabled tinyint NOT NULL DEFAULT 0 AFTER last_seen,
    ADD COLUMN discovery_status varchar(24) NOT NULL DEFAULT 'disabled' AFTER discovery_enabled,
    ADD COLUMN discovery_last_scan_at bigint DEFAULT NULL AFTER discovery_status,
    ADD COLUMN discovery_last_cidr varchar(255) DEFAULT NULL AFTER discovery_last_scan_at,
    ADD COLUMN discovery_last_error varchar(500) DEFAULT NULL AFTER discovery_last_cidr;

CREATE TABLE IF NOT EXISTS lan_discovered_service (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    connector_id bigint NOT NULL,
    user_id int NOT NULL,
    host varchar(45) NOT NULL,
    port int NOT NULL,
    service_type varchar(40) NOT NULL,
    service_name varchar(100) NOT NULL,
    product varchar(160) DEFAULT NULL,
    title varchar(160) DEFAULT NULL,
    confidence varchar(16) NOT NULL DEFAULT 'medium',
    `sensitive` tinyint NOT NULL DEFAULT 0,
    first_seen_at bigint NOT NULL,
    last_seen_at bigint NOT NULL,
    created_time bigint NOT NULL,
    updated_time bigint NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lan_service_endpoint (connector_id,host,port),
    KEY idx_lan_service_user (user_id,last_seen_at),
    KEY idx_lan_service_connector (connector_id,last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
