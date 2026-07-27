CREATE TABLE IF NOT EXISTS managed_certificate (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  zone_id bigint NOT NULL,
  domain varchar(253) NOT NULL,
  account_key text DEFAULT NULL,
  private_key text DEFAULT NULL,
  certificate_chain text DEFAULT NULL,
  issuer varchar(160) DEFAULT NULL,
  serial_number varchar(160) DEFAULT NULL,
  not_before bigint DEFAULT NULL,
  expires_at bigint DEFAULT NULL,
  state varchar(24) NOT NULL DEFAULT 'pending',
  last_error varchar(500) DEFAULT NULL,
  last_attempt_at bigint DEFAULT NULL,
  next_attempt_at bigint DEFAULT NULL,
  created_time bigint NOT NULL,
  updated_time bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_managed_certificate_domain (zone_id, domain),
  KEY idx_certificate_renewal (state, expires_at, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE domain_route
  ADD COLUMN ingress_mode varchar(24) NOT NULL DEFAULT 'passthrough' AFTER service_name,
  ADD COLUMN dns_zone_id bigint DEFAULT NULL AFTER ingress_mode,
  ADD COLUMN dns_record_id varchar(64) DEFAULT NULL AFTER dns_zone_id,
  ADD COLUMN certificate_id bigint DEFAULT NULL AFTER dns_record_id,
  ADD KEY idx_domain_certificate (certificate_id, state);
