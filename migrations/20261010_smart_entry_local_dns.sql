-- Normal upgrades add this column automatically at startup.
-- Existing groups keep their public DNS behavior; local groups do not write provider records.
ALTER TABLE smart_entry_group ADD COLUMN dns_mode varchar(16) NOT NULL DEFAULT 'public';
