-- Serialize port allocation across users and backend instances.
-- Safe to run more than once.

CREATE TABLE IF NOT EXISTS `port_allocation_lock` (
  `id` tinyint(3) unsigned NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `port_allocation_lock` (`id`, `updated_time`)
VALUES (1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000);
