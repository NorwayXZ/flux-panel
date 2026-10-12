package com.admin.config;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotent schema changes shared by the startup schema initializers.
 */
final class SchemaSupport {

    private SchemaSupport() {
    }

    /**
     * Adds a column unless the table already has it.
     *
     * @return true when the column was added by this call
     */
    static boolean ensureColumn(JdbcTemplate jdbcTemplate, String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (count != null && count > 0) {
            return false;
        }
        jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        return true;
    }

    /**
     * Adds a plain index over the given column list unless an index of that name exists.
     */
    static void ensureIndex(JdbcTemplate jdbcTemplate, String table, String index, String columns) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD KEY `" + index + "` (" + columns + ")");
        }
    }
}
