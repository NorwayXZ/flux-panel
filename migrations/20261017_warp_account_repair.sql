-- WARP self-repair and WARP+ licenses (2.82.1). Skip a line if its column already exists.
ALTER TABLE warp_account ADD COLUMN token varchar(512) DEFAULT NULL;
ALTER TABLE warp_account ADD COLUMN account_type varchar(32) DEFAULT NULL;
ALTER TABLE warp_account ADD COLUMN license_key varchar(255) DEFAULT NULL;
ALTER TABLE warp_account ADD COLUMN fail_count int NOT NULL DEFAULT 0;
