package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.Tunnel;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deletes everything that refers to a node, so that deleting a node never fails because something still uses it.
 *
 * <p>{@link #releaseRuntime} runs first and outside any transaction: it asks each feature to delete its resources
 * the normal way, which also removes Agent configuration and DNS records. Any of these may fail, for example when
 * the node is offline; {@link #purge} then removes the remaining rows in the node deletion transaction.</p>
 */
@Slf4j
@Component
public class NodeDeletionCascade {
    /** Every column that stores a node id. A schema test checks that new node columns are added here. */
    static final Set<String> NODE_COLUMNS = Set.of(
            "tunnel.in_node_id", "tunnel.out_node_id", "user_node.node_id",
            "smart_entry_route.entry_node_id", "smart_entry_activity_archive.entry_node_id",
            "cross_entry_failover_member.entry_node_id", "cross_entry_managed_resource.entry_node_id",
            "cross_entry_managed_cleanup.entry_node_id", "source_ip_entry_group.ingress_node_id",
            "aggregation_group.entry_node_id", "network_route_application.entry_node_id",
            "network_route_application.exit_node_id", "home_proxy_route.egress_node_id", "home_proxy_gateway.node_id",
            "dynamic_dns_rule.node_id", "nft_forward_rule.node_id", "nft_forward_event.node_id",
            "authorized_entry_forward.node_id", "cnix_shared_relay.out_node_id", "private_network_link.source_node_id",
            "private_network_link.target_node_id", "private_network_member.node_id", "virtual_lan_network.hub_node_id",
            "exit_group.node_id", "exit_event.node_id", "mysterium_account.node_id", "mysterium_connection.node_id",
            "mysterium_event.node_id", "mysterium_shared_member.node_id", "mysterium_shared_pool.owner_node_id",
            "warp_account.node_id", "docker_app_instance.node_id", "docker_app_event.node_id", "ip_quality_scan.node_id",
            "agent_identity_baseline.node_id", "agent_upgrade_task.node_id", "ai_node_note.node_id",
            "ai_ops_change.node_id", "terminal_session_audit.node_id", "service_telemetry_latest.node_id",
            "service_telemetry_sample.node_id", "service_traffic_daily.node_id", "node_metric_sample.node_id",
            "node_disk_sample.node_id", "quality_probe_task.source_node_id", "quality_probe_task.target_node_id",
            "quality_probe_run.source_node_id", "bandwidth_test_task.source_node_id",
            "bandwidth_test_task.target_node_id", "bandwidth_test_run.source_node_id",
            "bandwidth_test_run.target_node_id", "udp_quic_diagnostic_task.source_node_id",
            "udp_quic_diagnostic_task.target_node_id", "udp_quic_diagnostic_run.source_node_id",
            "udp_quic_diagnostic_run.target_node_id", "protocol_probe_run.node_id", "protocol_probe_run.probe_node_id",
            "system_self_check_run.scope_node_id", "domain_route.node_id", "domain_route.backend_node_id",
            "domain_route_backend.backend_node_id", "private_proxy.node_id", "server_asset.node_id",
            "port_pool.node_id");

    /** Node columns that are intentionally kept: upgrade batch history only names the nodes it touched. */
    static final Set<String> RETAINED_NODE_COLUMNS = Set.of("agent_upgrade_batch.current_node_id");

    /**
     * Statements run by {@link #purge}, children before parents. {@code :node}, {@code :tunnels},
     * {@code :forwards} and {@code :homeRoutes} stand for the deleted node and the ids deleted with it.
     */
    static final List<String> PURGE_STATEMENTS = List.of(
            // Strategies and failover groups: the services already released their DNS records when they could.
            "UPDATE smart_entry_route SET current_forward_id=NULL,dns_dirty=1 WHERE current_forward_id IN :forwards",
            "DELETE FROM smart_entry_route WHERE entry_node_id=:node OR forward_id IN :forwards",
            "DELETE FROM smart_entry_activity_archive WHERE entry_node_id=:node OR forward_id IN :forwards",
            "DELETE FROM cross_entry_failover_member WHERE entry_node_id=:node OR forward_id IN :forwards",
            "DELETE FROM cross_entry_managed_resource WHERE entry_node_id=:node OR forward_id IN :forwards OR tunnel_id IN :tunnels",
            "DELETE FROM cross_entry_managed_cleanup WHERE entry_node_id=:node OR forward_id IN :forwards OR tunnel_id IN :tunnels",
            "DELETE FROM source_ip_entry_route WHERE backend_forward_id IN :forwards "
                    + "OR group_id IN (SELECT id FROM source_ip_entry_group WHERE ingress_node_id=:node)",
            "DELETE FROM source_ip_entry_event WHERE group_id IN (SELECT id FROM source_ip_entry_group WHERE ingress_node_id=:node)",
            "DELETE FROM source_ip_entry_group WHERE ingress_node_id=:node",
            "UPDATE source_ip_entry_group SET default_route_id=NULL WHERE default_route_id NOT IN (SELECT id FROM source_ip_entry_route)",
            "DELETE FROM aggregation_member WHERE tunnel_id IN :tunnels OR group_id IN "
                    + "(SELECT id FROM aggregation_group WHERE entry_node_id=:node OR forward_id IN :forwards)",
            "DELETE FROM aggregation_event WHERE group_id IN "
                    + "(SELECT id FROM aggregation_group WHERE entry_node_id=:node OR forward_id IN :forwards)",
            "DELETE FROM aggregation_group WHERE entry_node_id=:node OR forward_id IN :forwards",
            "DELETE FROM network_route_application WHERE entry_node_id=:node OR exit_node_id=:node OR tunnel_id IN :tunnels",
            "DELETE FROM home_proxy_nat_event WHERE route_id IN :homeRoutes",
            "DELETE FROM home_proxy_gateway WHERE route_id IN :homeRoutes OR node_id=:node OR tunnel_id IN :tunnels",
            "DELETE FROM home_proxy_route WHERE id IN :homeRoutes",
            "UPDATE home_proxy_route SET dynamic_dns_rule_id=NULL WHERE dynamic_dns_rule_id IN "
                    + "(SELECT id FROM dynamic_dns_rule WHERE node_id=:node AND COALESCE(source_type,'node')<>'connector')",
            "DELETE FROM dynamic_dns_history WHERE rule_id IN "
                    + "(SELECT id FROM dynamic_dns_rule WHERE node_id=:node AND COALESCE(source_type,'node')<>'connector')",
            "DELETE FROM dynamic_dns_rule WHERE node_id=:node AND COALESCE(source_type,'node')<>'connector'",
            "UPDATE dynamic_dns_rule SET node_id=NULL WHERE node_id=:node",
            "DELETE FROM nft_forward_event WHERE node_id=:node OR rule_id IN (SELECT id FROM nft_forward_rule WHERE node_id=:node)",
            "DELETE FROM nft_forward_rule WHERE node_id=:node",
            "DELETE FROM authorized_entry_forward WHERE node_id=:node OR forward_id IN :forwards",
            "DELETE FROM cnix_shared_grant WHERE tunnel_id IN :tunnels OR forward_id IN :forwards",
            "DELETE FROM cnix_shared_relay WHERE out_node_id=:node OR tunnel_id IN :tunnels",
            "DELETE FROM private_network_link WHERE source_node_id=:node OR target_node_id=:node",
            "DELETE FROM private_network_member WHERE node_id=:node",
            "DELETE FROM virtual_lan_member WHERE (target_type='node' AND target_id=:node) "
                    + "OR network_id IN (SELECT id FROM virtual_lan_network WHERE hub_node_id=:node)",
            "DELETE FROM virtual_lan_network WHERE hub_node_id=:node",
            "DELETE FROM exit_member WHERE group_id IN (SELECT id FROM exit_group WHERE node_id=:node)",
            "DELETE FROM exit_event WHERE node_id=:node",
            "DELETE FROM exit_group WHERE node_id=:node",
            "DELETE FROM mysterium_connection WHERE node_id=:node",
            "DELETE FROM mysterium_event WHERE node_id=:node",
            "DELETE FROM mysterium_account WHERE node_id=:node",
            "DELETE FROM mysterium_shared_member WHERE node_id=:node",
            "DELETE FROM mysterium_shared_pool WHERE owner_node_id=:node",
            "DELETE FROM warp_account WHERE node_id=:node",
            "DELETE FROM docker_app_event WHERE node_id=:node",
            "DELETE FROM docker_app_instance WHERE node_id=:node",
            "DELETE FROM private_proxy_traffic WHERE proxy_id IN (SELECT id FROM private_proxy WHERE node_id=:node)",
            "DELETE FROM private_proxy WHERE node_id=:node",
            "DELETE FROM domain_route_backend WHERE backend_node_id=:node "
                    + "OR route_id IN (SELECT id FROM domain_route WHERE node_id=:node OR backend_node_id=:node)",
            "DELETE FROM domain_route WHERE node_id=:node OR backend_node_id=:node",
            "UPDATE docker_app_instance SET domain_route_id=NULL WHERE domain_route_id NOT IN (SELECT id FROM domain_route)",
            // Diagnostics, telemetry and history recorded for the node.
            "DELETE FROM quality_probe_sample WHERE run_id IN (SELECT id FROM quality_probe_run WHERE source_node_id=:node) "
                    + "OR task_id IN (SELECT id FROM quality_probe_task WHERE source_node_id=:node OR target_node_id=:node)",
            "DELETE FROM quality_probe_run WHERE source_node_id=:node "
                    + "OR task_id IN (SELECT id FROM quality_probe_task WHERE source_node_id=:node OR target_node_id=:node)",
            "DELETE FROM quality_probe_task WHERE source_node_id=:node OR target_node_id=:node",
            "DELETE FROM bandwidth_test_run WHERE source_node_id=:node OR target_node_id=:node "
                    + "OR task_id IN (SELECT id FROM bandwidth_test_task WHERE source_node_id=:node OR target_node_id=:node)",
            "DELETE FROM bandwidth_test_task WHERE source_node_id=:node OR target_node_id=:node",
            "DELETE FROM udp_quic_diagnostic_run WHERE source_node_id=:node OR target_node_id=:node "
                    + "OR task_id IN (SELECT id FROM udp_quic_diagnostic_task WHERE source_node_id=:node OR target_node_id=:node)",
            "DELETE FROM udp_quic_diagnostic_task WHERE source_node_id=:node OR target_node_id=:node",
            "DELETE FROM protocol_probe_run WHERE node_id=:node OR probe_node_id=:node",
            "DELETE FROM system_self_check_finding WHERE (resource_type='node' AND resource_id=:node) "
                    + "OR run_id IN (SELECT id FROM system_self_check_run WHERE scope_node_id=:node)",
            "DELETE FROM system_self_check_run WHERE scope_node_id=:node",
            "DELETE FROM monitoring_alert_read WHERE alert_id IN (SELECT id FROM monitoring_alert WHERE "
                    + "(resource_type='node' AND resource_id=:node) OR (resource_type='tunnel' AND resource_id IN :tunnels) "
                    + "OR (resource_type='forward' AND resource_id IN :forwards))",
            "DELETE FROM monitoring_alert WHERE (resource_type='node' AND resource_id=:node) "
                    + "OR (resource_type='tunnel' AND resource_id IN :tunnels) OR (resource_type='forward' AND resource_id IN :forwards)",
            "DELETE FROM monitoring_current WHERE (resource_type='node' AND resource_id=:node) "
                    + "OR (resource_type='tunnel' AND resource_id IN :tunnels) OR (resource_type='forward' AND resource_id IN :forwards)",
            "DELETE FROM monitoring_history WHERE (resource_type='node' AND resource_id=:node) "
                    + "OR (resource_type='tunnel' AND resource_id IN :tunnels) OR (resource_type='forward' AND resource_id IN :forwards)",
            "DELETE FROM ip_quality_scan WHERE node_id=:node",
            "DELETE FROM agent_identity_baseline WHERE node_id=:node",
            "DELETE FROM agent_upgrade_task WHERE node_id=:node",
            "DELETE FROM ai_node_note WHERE node_id=:node",
            "DELETE FROM ai_ops_change WHERE node_id=:node",
            "DELETE FROM terminal_session_audit WHERE node_id=:node",
            "DELETE FROM service_telemetry_latest WHERE node_id=:node",
            "DELETE FROM service_telemetry_sample WHERE node_id=:node",
            "DELETE FROM service_traffic_daily WHERE node_id=:node",
            "DELETE FROM node_metric_sample WHERE node_id=:node",
            "DELETE FROM node_disk_sample WHERE node_id=:node",
            // The server's purchase record outlives the node; only the link is removed.
            "UPDATE server_asset SET node_id=NULL WHERE node_id=:node",
            "DELETE FROM user_node WHERE node_id=:node",
            // Tunnels through the node and the forwards, grants and limits on them.
            "DELETE FROM forward_route_switch WHERE forward_id IN :forwards",
            "DELETE FROM forward WHERE id IN :forwards OR tunnel_id IN :tunnels",
            "DELETE FROM user_tunnel WHERE tunnel_id IN :tunnels",
            "DELETE FROM speed_limit WHERE tunnel_id IN :tunnels",
            "DELETE FROM tunnel WHERE id IN :tunnels OR in_node_id=:node OR out_node_id=:node");

    private static final Pattern PARAMETER = Pattern.compile(":(node|tunnels|forwards|homeRoutes)\\b");

    private final JdbcTemplate jdbc;

    @Resource @Lazy private TunnelService tunnelService;
    @Resource @Lazy private SmartEntryService smartEntryService;
    @Resource @Lazy private CrossEntryFailoverService crossEntryFailoverService;
    @Resource @Lazy private SourceIpEntryService sourceIpEntryService;
    @Resource @Lazy private MultiLineAggregationService aggregationService;
    @Resource @Lazy private NetworkRouteApplicationService routeApplicationService;
    @Resource @Lazy private HomeProxyService homeProxyService;
    @Resource @Lazy private DynamicDnsService dynamicDnsService;
    @Resource @Lazy private NftForwardService nftForwardService;
    @Resource @Lazy private PrivateProxyService privateProxyService;
    @Resource @Lazy private ServicePublishingService servicePublishingService;
    @Resource @Lazy private VirtualLanService virtualLanService;
    @Resource @Lazy private CnixSharedRelayService cnixSharedRelayService;

    public NodeDeletionCascade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Forwards on the given tunnels. */
    public List<Long> forwardIds(Collection<Long> tunnelIds) {
        if (tunnelIds.isEmpty()) return List.of();
        return jdbc.queryForList("SELECT id FROM forward WHERE tunnel_id IN (" + placeholders(tunnelIds.size()) + ")",
                Long.class, tunnelIds.toArray());
    }

    /**
     * Deletes the node's resources through their own services, so Agents and DNS providers are cleaned up where
     * possible. Failures are logged and counted only; {@link #purge} removes whatever is left.
     */
    public Map<String, Object> releaseRuntime(Long nodeId, boolean online, List<Tunnel> tunnels, Collection<Long> forwardIds) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        List<Long> tunnelIds = tunnels.stream().map(Tunnel::getId).toList();
        summary.put("smartEntryRouteCount", attempt(failures, "三网优化", () -> smartEntryService.detachNodeResources(nodeId, forwardIds), 0));
        summary.put("crossEntryMemberCount", attempt(failures, "入口容灾", () -> crossEntryFailoverService.detachNodeResources(nodeId, forwardIds), 0));
        summary.put("sourceIpEntryCount", deleteEach(failures, "来源 IP 分流",
                ids("SELECT id FROM source_ip_entry_group WHERE ingress_node_id=:node AND state<>'deleted'", nodeId, tunnelIds, forwardIds),
                sourceIpEntryService::delete));
        for (Long groupId : ids("SELECT DISTINCT group_id FROM source_ip_entry_route WHERE backend_forward_id IN :forwards", nodeId, tunnelIds, forwardIds)) {
            // The group lives on another node and keeps its other routes; resync it without the deleted backends.
            attempt(failures, "来源 IP 分流", () -> {
                Bound routes = bind("DELETE FROM source_ip_entry_route WHERE group_id=? AND backend_forward_id IN :forwards",
                        null, tunnelIds, forwardIds, List.of());
                List<Object> arguments = new ArrayList<>(List.of(groupId));
                arguments.addAll(List.of(routes.arguments()));
                jdbc.update(routes.sql(), arguments.toArray());
                jdbc.update("UPDATE source_ip_entry_group SET default_route_id=NULL WHERE id=? AND default_route_id NOT IN "
                        + "(SELECT id FROM source_ip_entry_route)", groupId);
                return sourceIpEntryService.check(groupId);
            }, null);
        }
        summary.put("aggregationGroupCount", deleteEach(failures, "多线路并发调度",
                ids("SELECT id FROM aggregation_group WHERE entry_node_id=:node OR forward_id IN :forwards", nodeId, tunnelIds, forwardIds),
                aggregationService::delete));
        summary.put("routeApplicationCount", deleteEach(failures, "出口应用",
                ids("SELECT id FROM network_route_application WHERE state<>'deleted' AND (entry_node_id=:node OR exit_node_id=:node "
                        + "OR tunnel_id IN :tunnels)", nodeId, tunnelIds, forwardIds), routeApplicationService::delete));
        summary.put("homeProxyCount", deleteEach(failures, "家庭代理",
                homeProxyRoutes(nodeId, tunnelIds, forwardIds, true), homeProxyService::delete));
        summary.put("dynamicDnsRuleCount", deleteEach(failures, "动态 DNS",
                ids("SELECT id FROM dynamic_dns_rule WHERE node_id=:node AND COALESCE(source_type,'node')<>'connector'",
                        nodeId, tunnelIds, forwardIds), dynamicDnsService::deleteRule));
        List<Long> nftRules = ids("SELECT id FROM nft_forward_rule WHERE node_id=:node AND state<>'deleted'", nodeId, tunnelIds, forwardIds);
        summary.put("nftForwardCount", online ? deleteEach(failures, "nftables 转发", nftRules, nftForwardService::delete) : nftRules.size());
        summary.put("privateProxyCount", attempt(failures, "私人代理", () -> privateProxyService.deleteForNode(nodeId), 0));
        summary.put("domainRouteCount", attempt(failures, "域名入口", () -> servicePublishingService.cleanupDomainRoutesForNode(nodeId), 0));
        summary.put("virtualLanCount", deleteEach(failures, "虚拟局域网",
                ids("SELECT id FROM virtual_lan_network WHERE hub_node_id=:node", nodeId, tunnelIds, forwardIds), virtualLanService::delete));
        for (Tunnel tunnel : tunnels) {
            if (online) {
                // Removes the forwards' Agent services on every node of the path, not just this one.
                deleteEach(failures, "隧道 " + tunnel.getName(), List.of(tunnel.getId()), tunnelService::deleteTunnel);
            } else {
                attempt(failures, "共享出口 " + tunnel.getName(), () -> { cnixSharedRelayService.delete(tunnel); return null; }, null);
            }
        }
        summary.put("runtimeCleanupFailures", failures);
        return summary;
    }

    /** Removes every remaining row that refers to the node, its tunnels or their forwards; returns deleted rows by table. */
    public Map<String, Integer> purge(Long nodeId, Collection<Long> tunnelIds, Collection<Long> forwardIds) {
        List<Long> homeRoutes = homeProxyRoutes(nodeId, tunnelIds, forwardIds, false);
        Map<String, Integer> removed = new LinkedHashMap<>();
        for (String statement : PURGE_STATEMENTS) {
            int count = run(statement, nodeId, tunnelIds, forwardIds, homeRoutes);
            if (count > 0 && statement.startsWith("DELETE")) removed.merge(table(statement), count, Integer::sum);
        }
        return removed;
    }

    /** Port pools on the node; they are released after the node is gone, through the orphaned pool cleanup. */
    public List<Long> portPools(Long nodeId) {
        return ids("SELECT id FROM port_pool WHERE node_id=:node AND status<>0", nodeId, List.of(), List.of());
    }

    /**
     * Releases pools whose node was deleted: published services, domain routes, leases and grants on them. If the
     * normal cleanup fails, leases and grants are dropped and the pool is retired anyway.
     */
    public int releasePortPools(Collection<Long> poolIds) {
        for (Long poolId : poolIds) {
            R result = attempt(new ArrayList<>(), "端口池", () -> servicePublishingService.deletePortPool(poolId, true), null);
            if (result != null && result.getCode() == 0) continue;
            long now = System.currentTimeMillis();
            run("DELETE FROM port_lease_event WHERE lease_id IN (SELECT id FROM port_lease WHERE pool_id=:node)", poolId, List.of(), List.of());
            run("DELETE FROM port_lease WHERE pool_id=:node", poolId, List.of(), List.of());
            run("DELETE FROM port_pool_grant WHERE pool_id=:node", poolId, List.of(), List.of());
            jdbc.update("UPDATE published_service SET state='deleted',last_error='端口池所属节点已删除',updated_time=? "
                    + "WHERE pool_id=? AND state NOT IN ('released','deleted')", now, poolId);
            jdbc.update("UPDATE port_pool SET status=0,updated_time=? WHERE id=?", now, poolId);
        }
        return poolIds.size();
    }

    private List<Long> homeProxyRoutes(Long nodeId, Collection<Long> tunnelIds, Collection<Long> forwardIds, boolean activeOnly) {
        return ids("SELECT id FROM home_proxy_route WHERE " + (activeOnly ? "state<>'deleted' AND " : "")
                + "(egress_node_id=:node OR egress_tunnel_id IN :tunnels OR id IN "
                + "(SELECT route_id FROM home_proxy_gateway WHERE node_id=:node OR tunnel_id IN :tunnels))", nodeId, tunnelIds, forwardIds);
    }

    private int deleteEach(List<String> failures, String label, List<Long> ids, java.util.function.Function<Long, R> delete) {
        for (Long id : ids) {
            R result = attempt(failures, label, () -> delete.apply(id), null);
            if (result != null && result.getCode() != 0) failures.add(label + " " + id + "：" + result.getMsg());
        }
        return ids.size();
    }

    private <T> T attempt(List<String> failures, String label, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            log.warn("Node deletion could not clean up {}: {}", label, e.getMessage());
            failures.add(label + "：" + e.getMessage());
            return fallback;
        }
    }

    private List<Long> ids(String template, Long nodeId, Collection<Long> tunnelIds, Collection<Long> forwardIds) {
        Bound bound = bind(template, nodeId, tunnelIds, forwardIds, List.of());
        try {
            return jdbc.queryForList(bound.sql(), Long.class, bound.arguments());
        } catch (BadSqlGrammarException e) {
            return List.of();
        }
    }

    private int run(String template, Long nodeId, Collection<Long> tunnelIds, Collection<Long> forwardIds) {
        return run(template, nodeId, tunnelIds, forwardIds, List.of());
    }

    private int run(String template, Long nodeId, Collection<Long> tunnelIds, Collection<Long> forwardIds, Collection<Long> homeRoutes) {
        Bound bound = bind(template, nodeId, tunnelIds, forwardIds, homeRoutes);
        try {
            return jdbc.update(bound.sql(), bound.arguments());
        } catch (BadSqlGrammarException e) {
            // Optional feature tables are created at startup and may be missing on databases that never used them.
            log.debug("Skip node deletion statement {}: {}", table(template), e.getMessage());
            return 0;
        }
    }

    static Bound bind(String template, Long nodeId, Collection<Long> tunnelIds, Collection<Long> forwardIds, Collection<Long> homeRoutes) {
        Matcher matcher = PARAMETER.matcher(template);
        StringBuilder sql = new StringBuilder();
        List<Object> arguments = new ArrayList<>();
        while (matcher.find()) {
            Collection<Long> values = switch (matcher.group(1)) {
                case "tunnels" -> tunnelIds;
                case "forwards" -> forwardIds;
                case "homeRoutes" -> homeRoutes;
                default -> null;
            };
            if (values == null) {
                matcher.appendReplacement(sql, "?");
                arguments.add(nodeId);
            } else if (values.isEmpty()) {
                matcher.appendReplacement(sql, "(NULL)");
            } else {
                matcher.appendReplacement(sql, "(" + placeholders(values.size()) + ")");
                arguments.addAll(values);
            }
        }
        matcher.appendTail(sql);
        return new Bound(sql.toString(), arguments.toArray());
    }

    private static String table(String statement) {
        String[] words = statement.split("\\s+");
        return words[0].equals("UPDATE") ? words[1] : words[2];
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    record Bound(String sql, Object[] arguments) {}
}
