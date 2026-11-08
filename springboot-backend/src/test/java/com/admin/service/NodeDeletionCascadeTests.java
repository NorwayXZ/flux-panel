package com.admin.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeDeletionCascadeTests {
    private static final Pattern CREATE = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern NODE_COLUMN = Pattern.compile("(?<![\\w.])`?(\\w*node_id)`?\\s+(?:bigint|int)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENSURE_COLUMN = Pattern.compile("ensure\\w*Column\\(\\s*(?:\\w+\\s*,\\s*)?\"(\\w+)\"\\s*,\\s*\"(\\w*node_id)\"");
    private static final Pattern ALTER = Pattern.compile("ALTER\\s+TABLE\\s+`?(\\w+)`?([^;]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_ADD = Pattern.compile("ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w*node_id)`?\\s+(?:bigint|int)", Pattern.CASE_INSENSITIVE);

    @Test
    void everyNodeColumnInTheSchemaIsCleanedUpWhenTheNodeIsDeleted() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        assertTrue(Files.exists(root.resolve("gost.sql")), "repository root not found from " + root);
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Stream.of(root.resolve("gost.sql"), root.resolve("migrations"),
                root.resolve("springboot-backend/src/main")).flatMap(NodeDeletionCascadeTests::walk)) {
            files.filter(path -> path.toString().endsWith(".sql") || path.toString().endsWith(".java"))
                    .forEach(path -> found.addAll(nodeColumns(read(path))));
        }
        assertTrue(found.contains("smart_entry_route.entry_node_id"), "schema scan found " + found);

        Set<String> missing = new TreeSet<>(found);
        missing.removeAll(NodeDeletionCascade.NODE_COLUMNS);
        missing.removeAll(NodeDeletionCascade.RETAINED_NODE_COLUMNS);
        assertEquals(Set.of(), missing, "add these columns to NodeDeletionCascade so deleting a node cleans them up");

        String statements = String.join("\n", NodeDeletionCascade.PURGE_STATEMENTS);
        for (String column : NodeDeletionCascade.NODE_COLUMNS) {
            String[] parts = column.split("\\.");
            if (parts[0].equals("port_pool")) continue; // released after the node row is gone
            if (column.equals("home_proxy_route.egress_node_id")) continue; // selects the :homeRoutes ids
            assertTrue(Pattern.compile("(UPDATE|FROM) " + parts[0] + " [^\\n]*" + parts[1]).matcher(statements).find(),
                    "no purge statement handles " + column);
        }
    }

    @Test
    void purgeRemovesRowsOfTheDeletedNodeOnly() {
        JdbcTemplate jdbc = database();
        jdbc.execute("CREATE TABLE tunnel(id bigint PRIMARY KEY,in_node_id bigint,out_node_id bigint)");
        jdbc.execute("CREATE TABLE forward(id bigint PRIMARY KEY,tunnel_id bigint)");
        jdbc.execute("CREATE TABLE forward_route_switch(id bigint PRIMARY KEY,forward_id bigint)");
        jdbc.execute("CREATE TABLE user_tunnel(id bigint PRIMARY KEY,tunnel_id bigint)");
        jdbc.execute("CREATE TABLE speed_limit(id bigint PRIMARY KEY,tunnel_id bigint)");
        jdbc.execute("CREATE TABLE user_node(id bigint PRIMARY KEY,node_id bigint)");
        jdbc.execute("CREATE TABLE smart_entry_route(id bigint PRIMARY KEY,group_id bigint,entry_node_id bigint,forward_id bigint,"
                + "current_forward_id bigint,dns_dirty tinyint DEFAULT 0)");
        jdbc.execute("CREATE TABLE cross_entry_failover_member(id bigint PRIMARY KEY,group_id bigint,entry_node_id bigint,forward_id bigint)");
        jdbc.execute("CREATE TABLE dynamic_dns_rule(id bigint PRIMARY KEY,source_type varchar(16),node_id bigint)");
        jdbc.execute("CREATE TABLE dynamic_dns_history(id bigint PRIMARY KEY,rule_id bigint)");
        jdbc.execute("CREATE TABLE home_proxy_route(id bigint PRIMARY KEY,state varchar(16),egress_node_id bigint,egress_tunnel_id bigint,"
                + "dynamic_dns_rule_id bigint)");
        jdbc.execute("CREATE TABLE home_proxy_gateway(id bigint PRIMARY KEY,route_id bigint,node_id bigint,tunnel_id bigint)");
        jdbc.execute("CREATE TABLE nft_forward_rule(id bigint PRIMARY KEY,node_id bigint)");
        jdbc.execute("CREATE TABLE server_asset(id bigint PRIMARY KEY,node_id bigint)");
        jdbc.execute("CREATE TABLE monitoring_current(resource_type varchar(16),resource_id bigint)");

        jdbc.update("INSERT INTO tunnel VALUES (10,36,37),(11,37,38)");
        jdbc.update("INSERT INTO forward VALUES (100,10),(101,11)");
        jdbc.update("INSERT INTO forward_route_switch VALUES (1,100),(2,101)");
        jdbc.update("INSERT INTO user_tunnel VALUES (1,10),(2,11)");
        jdbc.update("INSERT INTO speed_limit VALUES (1,10),(2,11)");
        jdbc.update("INSERT INTO user_node VALUES (1,36),(2,37)");
        jdbc.update("INSERT INTO smart_entry_route VALUES (1,5,36,100,100,0),(2,5,37,101,100,0),(3,6,37,101,101,0)");
        jdbc.update("INSERT INTO cross_entry_failover_member VALUES (1,7,36,100),(2,7,37,101)");
        jdbc.update("INSERT INTO dynamic_dns_rule VALUES (1,'node',36),(2,'connector',36),(3,'node',37)");
        jdbc.update("INSERT INTO dynamic_dns_history VALUES (1,1),(2,3)");
        jdbc.update("INSERT INTO home_proxy_route VALUES (1,'active',NULL,NULL,3),(2,'active',36,NULL,NULL),(3,'active',NULL,NULL,1)");
        jdbc.update("INSERT INTO home_proxy_gateway VALUES (1,1,36,NULL),(2,3,37,11)");
        jdbc.update("INSERT INTO nft_forward_rule VALUES (1,36),(2,37)");
        jdbc.update("INSERT INTO server_asset VALUES (1,36),(2,37)");
        jdbc.update("INSERT INTO monitoring_current VALUES ('node',36),('node',37),('tunnel',10),('forward',100),('forward',101)");

        Map<String, Integer> removed = new NodeDeletionCascade(jdbc).purge(36L, List.of(10L), List.of(100L));

        assertEquals(List.of(11L), ids(jdbc, "SELECT id FROM tunnel"));
        assertEquals(List.of(101L), ids(jdbc, "SELECT id FROM forward"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM forward_route_switch"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM user_tunnel"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM speed_limit"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM user_node"));
        assertEquals(List.of(2L, 3L), ids(jdbc, "SELECT id FROM smart_entry_route"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM smart_entry_route WHERE current_forward_id IS NULL AND dns_dirty=1"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM cross_entry_failover_member"));
        assertEquals(List.of(2L, 3L), ids(jdbc, "SELECT id FROM dynamic_dns_rule"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM dynamic_dns_rule WHERE node_id IS NULL"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM dynamic_dns_history"));
        assertEquals(List.of(3L), ids(jdbc, "SELECT id FROM home_proxy_route"));
        assertEquals(List.of(3L), ids(jdbc, "SELECT id FROM home_proxy_route WHERE dynamic_dns_rule_id IS NULL"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM home_proxy_gateway"));
        assertEquals(List.of(2L), ids(jdbc, "SELECT id FROM nft_forward_rule"));
        assertEquals(List.of(1L), ids(jdbc, "SELECT id FROM server_asset WHERE node_id IS NULL"));
        assertEquals(List.of(37L, 101L), ids(jdbc, "SELECT resource_id FROM monitoring_current"));
        assertEquals(1, removed.get("tunnel"));
        assertEquals(2, removed.get("home_proxy_route"));
    }

    @Test
    void lazyServiceReferencesResolveInASpringContext() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SmartEntryService smartEntry = mock(SmartEntryService.class);
        when(smartEntry.detachNodeResources(anyLong(), any())).thenReturn(3);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Singletons skip annotation processing, so the mocks' inherited @Resource fields stay empty.
            context.getBeanFactory().registerSingleton("jdbcTemplate", jdbc);
            context.getBeanFactory().registerSingleton("smartEntryService", smartEntry);
            for (Class<?> type : List.of(TunnelService.class, CrossEntryFailoverService.class, SourceIpEntryService.class,
                    MultiLineAggregationService.class, NetworkRouteApplicationService.class, HomeProxyService.class,
                    DynamicDnsService.class, NftForwardService.class, PrivateProxyService.class,
                    ServicePublishingService.class, VirtualLanService.class, CnixSharedRelayService.class)) {
                context.getBeanFactory().registerSingleton(type.getSimpleName(), mock(type));
            }
            context.registerBean(NodeDeletionCascade.class);
            context.refresh();

            Map<String, Object> summary = context.getBean(NodeDeletionCascade.class).releaseRuntime(36L, false, List.of(), List.of());

            assertEquals(3, summary.get("smartEntryRouteCount"));
            assertEquals(List.of(), summary.get("runtimeCleanupFailures"));
            verify(smartEntry).detachNodeResources(36L, List.of());
        }
    }

    @Test
    void emptyIdListsMatchNothing() {
        NodeDeletionCascade.Bound bound = NodeDeletionCascade.bind(
                "DELETE FROM forward WHERE id IN :forwards OR tunnel_id IN :tunnels OR node_id=:node", 36L, List.of(10L, 11L), List.of(), List.of());
        assertEquals("DELETE FROM forward WHERE id IN (NULL) OR tunnel_id IN (?,?) OR node_id=?", bound.sql());
        assertArrayEquals(new Object[]{10L, 11L, 36L}, bound.arguments());
    }

    private static Set<String> nodeColumns(String text) {
        Set<String> columns = new TreeSet<>();
        Matcher create = CREATE.matcher(text);
        while (create.find()) {
            String table = create.group(1).toLowerCase(Locale.ROOT);
            Matcher column = NODE_COLUMN.matcher(body(text, create.end()));
            while (column.find()) columns.add(table + "." + column.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher ensure = ENSURE_COLUMN.matcher(text);
        while (ensure.find()) columns.add(ensure.group(1).toLowerCase(Locale.ROOT) + "." + ensure.group(2).toLowerCase(Locale.ROOT));
        Matcher alter = ALTER.matcher(text);
        while (alter.find()) {
            Matcher add = ALTER_ADD.matcher(alter.group(2));
            while (add.find()) columns.add(alter.group(1).toLowerCase(Locale.ROOT) + "." + add.group(1).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    /** The column list of a CREATE TABLE statement, up to its closing parenthesis. */
    private static String body(String text, int start) {
        int depth = 1;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return text.substring(start, i);
        }
        return text.substring(start);
    }

    private static Stream<Path> walk(Path path) {
        try {
            return Files.isDirectory(path) ? Files.walk(path).filter(Files::isRegularFile).toList().stream() : Stream.of(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JdbcTemplate database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:node-delete" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(source);
    }

    private static List<Long> ids(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForList(sql + " ORDER BY 1", Long.class);
    }
}
