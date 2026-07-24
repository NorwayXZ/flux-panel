-- Resource ownership and read-only node sharing.
-- Back up the database before applying this migration.

SET @admin_user_id := (
  SELECT `id` FROM `user` WHERE `role_id` = 0 ORDER BY `id` LIMIT 1
);
SET @admin_user_id := IFNULL(@admin_user_id, 1);

ALTER TABLE `node`
  ADD COLUMN `owner_user_id` int(10) NULL AFTER `id`;

UPDATE `node` SET `owner_user_id` = @admin_user_id WHERE `owner_user_id` IS NULL;

ALTER TABLE `node`
  MODIFY `owner_user_id` int(10) NOT NULL,
  ADD KEY `idx_node_owner_user_id` (`owner_user_id`);

ALTER TABLE `tunnel`
  ADD COLUMN `owner_user_id` int(10) NULL AFTER `id`;

UPDATE `tunnel` SET `owner_user_id` = @admin_user_id WHERE `owner_user_id` IS NULL;

ALTER TABLE `tunnel`
  MODIFY `owner_user_id` int(10) NOT NULL,
  ADD KEY `idx_tunnel_owner_user_id` (`owner_user_id`);

CREATE TABLE `user_node` (
  `id` int(10) NOT NULL AUTO_INCREMENT,
  `user_id` int(10) NOT NULL,
  `node_id` int(10) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_node` (`user_id`, `node_id`),
  KEY `idx_user_node_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
