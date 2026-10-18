-- Applied idempotently by PrivateProxySchemaInitializer during startup.
-- scope_id=0 preserves existing node/country shared connections and history.
ALTER TABLE private_proxy ADD COLUMN mysterium_independent tinyint NOT NULL DEFAULT 0;
ALTER TABLE mysterium_connection ADD COLUMN scope_id bigint NOT NULL DEFAULT 0;
ALTER TABLE mysterium_connection DROP PRIMARY KEY, ADD PRIMARY KEY(node_id,country,scope_id);
ALTER TABLE mysterium_event ADD COLUMN scope_id bigint NOT NULL DEFAULT 0;
