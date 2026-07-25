-- Store drag-and-drop card order separately for each panel user.
-- Safe to run more than once.

CREATE TABLE IF NOT EXISTS `layout_preference` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `scope` varchar(64) NOT NULL,
  `item_order` text NOT NULL,
  `updated_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_layout_preference_user_scope` (`user_id`,`scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
