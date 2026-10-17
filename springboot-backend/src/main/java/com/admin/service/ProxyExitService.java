package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Custom exits: proxies the user already has elsewhere (VLESS, Trojan, Hysteria2, ...), added by
 * pasting a share link or Clash node, that private proxies can send chosen traffic through.
 */
@Service
public class ProxyExitService {
    public static final String ROUTER_AGENT_VERSION = "2.76.0";
    /** Exit groups and the regex, port, network and GEOIP rules. */
    public static final String ROUTER_RULES_AGENT_VERSION = "2.79.0";
    /** Cloudflare WARP exits (WireGuard endpoints in the router). */
    public static final String WARP_AGENT_VERSION = "2.82.0";
    /** Stored outbound type of an exit group; the router gets a sing-box urltest. */
    public static final String GROUP_TYPE = "group";
    static final String DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204";
    private static final int MAX_EXITS_PER_USER = 200;

    private final JdbcTemplate jdbc;
    private final NodeMapper nodeMapper;
    private final AESCrypto crypto;

    private final WarpService warp;

    public ProxyExitService(JdbcTemplate jdbc, NodeMapper nodeMapper, WarpService warp, @Value("${jwt-secret}") String secret) {
        this.warp = warp;
        this.jdbc = jdbc;
        this.nodeMapper = nodeMapper;
        this.crypto = new AESCrypto(secret + ":proxy-exit");
    }

    public R list() {
        boolean admin = isAdmin();
        List<Map<String, Object>> rows = admin
                ? jdbc.queryForList("SELECT e.*, u.user AS owner_name FROM proxy_exit e LEFT JOIN user u ON u.id = e.user_id ORDER BY e.id DESC")
                : jdbc.queryForList("SELECT e.*, u.user AS owner_name FROM proxy_exit e LEFT JOIN user u ON u.id = e.user_id "
                + "WHERE e.user_id = ? ORDER BY e.id DESC", currentUserId());
        List<Map<String, Object>> exits = new ArrayList<>();
        for (Map<String, Object> row : rows) exits.add(view(row));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exits", exits);
        result.put("presets", ProxyRoutePresets.list());
        result.put("minimumAgentVersion", ROUTER_AGENT_VERSION);
        result.put("rulesAgentVersion", ROUTER_RULES_AGENT_VERSION);
        result.put("warpAgentVersion", WARP_AGENT_VERSION);
        return R.ok(result);
    }

    /**
     * Creates or updates an exit group: the router keeps testing its members and uses the
     * fastest one that answers.
     */
    public R saveGroup(Long id, String name, List<Long> members, boolean direct, boolean warp, String url, Integer intervalMinutes,
                       Integer tolerance) {
        if (name == null || name.isBlank()) return R.err("请输入名称");
        java.util.LinkedHashSet<Long> unique = new java.util.LinkedHashSet<>(members == null ? List.of() : members);
        if (unique.size() + (direct ? 1 : 0) + (warp ? 1 : 0) < 2) return R.err("出口组至少需要两个成员");
        if (unique.size() > 30) return R.err("出口组最多 30 个成员");
        String testUrl = url == null || url.isBlank() ? DEFAULT_TEST_URL : url.trim();
        if (!testUrl.matches("https?://[^\\s/]+(/\\S*)?") || testUrl.length() > 500) return R.err("测速地址需要以 http:// 或 https:// 开头");
        int interval = intervalMinutes == null ? 3 : intervalMinutes;
        if (interval < 1 || interval > 1440) return R.err("测速间隔需要在 1 到 1440 分钟之间");
        int toleranceMs = tolerance == null ? 50 : tolerance;
        if (toleranceMs < 0 || toleranceMs > 5000) return R.err("切换容差需要在 0 到 5000 毫秒之间");
        Integer userId = currentUserId();
        for (Long member : unique) {
            Map<String, Object> row = owned(member);
            if (row == null) return R.err("出口 #" + member + " 不存在或不能使用");
            if (GROUP_TYPE.equals(row.get("protocol"))) return R.err("出口组不能包含另一个出口组");
            if (id != null && id.equals(member)) return R.err("出口组不能包含自己");
        }
        JSONObject outbound = new JSONObject(true);
        outbound.put("type", GROUP_TYPE);
        outbound.put("members", new ArrayList<>(unique));
        outbound.put("direct", direct);
        outbound.put("warp", warp);
        outbound.put("url", testUrl);
        outbound.put("intervalMinutes", interval);
        outbound.put("tolerance", toleranceMs);
        String memberColumn = "," + String.join(",", unique.stream().map(String::valueOf).toList()) + ",";
        long now = System.currentTimeMillis();
        if (id == null) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM proxy_exit WHERE user_id = ?", Long.class, userId);
            if (count != null && count >= MAX_EXITS_PER_USER) return R.err("自定义出口最多 " + MAX_EXITS_PER_USER + " 个");
            jdbc.update("INSERT INTO proxy_exit (user_id,name,protocol,server,port,outbound,members,created_time,updated_time) VALUES (?,?,?,?,?,?,?,?,?)",
                    userId, truncate(name.trim(), 100), GROUP_TYPE, "", 0, crypto.encrypt(outbound.toJSONString()), memberColumn, now, now);
            return R.ok();
        }
        Map<String, Object> row = owned(id);
        if (row == null || !GROUP_TYPE.equals(row.get("protocol"))) return R.err("出口组不存在");
        jdbc.update("UPDATE proxy_exit SET name=?, outbound=?, members=?, updated_time=? WHERE id=?",
                truncate(name.trim(), 100), crypto.encrypt(outbound.toJSONString()), memberColumn, now, id);
        return R.ok();
    }

    /** Running proxies using this exit, directly or through a group, so a group edit can be re-applied. */
    public List<Long> groupsContaining(Long id) {
        return jdbc.queryForList("SELECT id FROM proxy_exit WHERE protocol = ? AND members LIKE ?", Long.class, GROUP_TYPE, "%," + id + ",%");
    }

    /** Shows what a pasted text contains without saving it. */
    public R parse(String text) {
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (ProxyExitLinks.Parsed parsed : ProxyExitLinks.parse(text)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", parsed.name);
                item.put("protocol", parsed.protocol);
                item.put("server", parsed.server);
                item.put("port", parsed.port);
                items.add(item);
            }
            return R.ok(items);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }

    public R create(String text, String name) {
        List<ProxyExitLinks.Parsed> parsed;
        try {
            parsed = ProxyExitLinks.parse(text);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        Integer userId = currentUserId();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM proxy_exit WHERE user_id = ?", Long.class, userId);
        if (count != null && count + parsed.size() > MAX_EXITS_PER_USER) return R.err("自定义出口最多 " + MAX_EXITS_PER_USER + " 个");
        long now = System.currentTimeMillis();
        int created = 0;
        for (ProxyExitLinks.Parsed item : parsed) {
            String label = parsed.size() == 1 && name != null && !name.isBlank() ? name.trim() : item.name;
            jdbc.update("INSERT INTO proxy_exit (user_id,name,protocol,server,port,outbound,created_time,updated_time) VALUES (?,?,?,?,?,?,?,?)",
                    userId, truncate(label, 100), item.protocol, truncate(item.server, 253), item.port,
                    crypto.encrypt(JSON.toJSONString(item.outbound)), now, now);
            created++;
        }
        return R.ok(Map.of("created", created));
    }

    public R rename(Long id, String name) {
        if (name == null || name.isBlank()) return R.err("请输入名称");
        if (owned(id) == null) return R.err("出口不存在");
        jdbc.update("UPDATE proxy_exit SET name=?, updated_time=? WHERE id=?", truncate(name.trim(), 100), System.currentTimeMillis(), id);
        return R.ok();
    }

    public R delete(Long id) {
        if (owned(id) == null) return R.err("出口不存在");
        List<Long> groups = groupsContaining(id);
        if (!groups.isEmpty()) return R.err("出口组“" + exitName(groups.get(0)) + "”包含这个出口，请先把它从组里移除");
        Long used = jdbc.queryForObject("SELECT COUNT(*) FROM private_proxy WHERE state <> 'deleted' AND (exit_custom_id = ? "
                + "OR route_rules LIKE ?)", Long.class, id, "%\"custom:" + id + "\"%");
        if (used != null && used > 0) return R.err("还有 " + used + " 个代理在使用这个出口，请先在代理的出口设置里换掉它");
        jdbc.update("DELETE FROM proxy_exit WHERE id=?", id);
        return R.ok();
    }

    /** Connects through the exit from a node and records the address the internet sees. */
    public R test(Long id, Long nodeId) {
        Map<String, Object> row = owned(id);
        if (row == null) return R.err("出口不存在");
        Node node = nodeId == null ? pickTestNode() : nodeMapper.selectById(nodeId);
        if (node == null) return R.err("没有可用于自检的在线节点（需要 Agent " + ROUTER_AGENT_VERSION + " 或更高版本）");
        if (!WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), ROUTER_AGENT_VERSION)) {
            return R.err("自检需要节点 Agent " + ROUTER_AGENT_VERSION + " 或更高版本");
        }
        JSONObject outbound = outbound(row);
        if (GROUP_TYPE.equals(outbound.getString("type"))) return testGroup(id, outbound, node);
        GostDto response = WebSocketServer.send_msg(node.getId(), Map.of("outbound", outbound), "TestProxyExit", 25);
        long now = System.currentTimeMillis();
        if (response == null || !"OK".equals(response.getMsg())) {
            String error = response == null ? "节点无响应" : response.getMsg();
            jdbc.update("UPDATE proxy_exit SET last_test_at=?, last_test_ok=0, last_test_error=? WHERE id=?", now, truncate(error, 500), id);
            return R.err("自检失败：" + WarpService.explainRuntimeError(error));
        }
        JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
        jdbc.update("UPDATE proxy_exit SET last_test_at=?, last_test_ok=1, last_test_ip=?, last_test_country=?, last_test_latency=?, "
                        + "last_test_error=NULL WHERE id=?", now, data.getString("ip"), data.getString("country"),
                data.getInteger("latencyMs"), id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ip", data.getString("ip"));
        result.put("country", data.getString("country"));
        result.put("latencyMs", data.getInteger("latencyMs"));
        result.put("nodeName", node.getName());
        return R.ok(result);
    }

    /** Tests every member of a group; the group counts as usable when one member is. */
    private R testGroup(Long id, JSONObject group, Node node) {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> best = null;
        List<Long> members = new ArrayList<>(group.getJSONArray("members").toJavaList(Long.class));
        if (group.getBooleanValue("warp")) members.add(null);
        for (Long member : members) {
            R tested = member == null ? warp.testOn(node) : test(member, node.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", member);
            item.put("name", member == null ? "Cloudflare WARP" : exitName(member));
            item.put("ok", tested.getCode() == 0);
            if (tested.getCode() == 0) {
                @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) tested.getData();
                item.putAll(data);
                if (best == null || ((Number) data.get("latencyMs")).intValue() < ((Number) best.get("latencyMs")).intValue()) best = item;
            } else {
                item.put("error", tested.getMsg());
            }
            results.add(item);
        }
        long now = System.currentTimeMillis();
        if (best == null) {
            jdbc.update("UPDATE proxy_exit SET last_test_at=?, last_test_ok=0, last_test_error=? WHERE id=?", now, "所有成员都不可用", id);
            return R.err("出口组的成员都不可用");
        }
        jdbc.update("UPDATE proxy_exit SET last_test_at=?, last_test_ok=1, last_test_ip=?, last_test_country=?, last_test_latency=?, "
                + "last_test_error=NULL WHERE id=?", now, best.get("ip"), best.get("country"), best.get("latencyMs"), id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ip", best.get("ip"));
        result.put("country", best.get("country"));
        result.put("latencyMs", best.get("latencyMs"));
        result.put("nodeName", node.getName());
        result.put("members", results);
        return R.ok(result);
    }

    /** The sing-box outbound of an exit the given user may use, or null. */
    JSONObject usableOutbound(Long id, Integer userId, boolean admin) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM proxy_exit WHERE id = ?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        if (!admin && !Objects.equals(((Number) row.get("user_id")).intValue(), userId)) return null;
        return outbound(row);
    }

    String exitName(Long id) {
        List<String> names = jdbc.queryForList("SELECT name FROM proxy_exit WHERE id = ?", String.class, id);
        return names.isEmpty() ? null : names.get(0);
    }

    private JSONObject outbound(Map<String, Object> row) {
        return JSON.parseObject(crypto.decryptString((String) row.get("outbound")));
    }

    private Node pickTestNode() {
        for (Node node : nodeMapper.selectList(null)) {
            if (WebSocketServer.isNodeOnline(node.getId()) && AgentVersionUtil.isAtLeast(node.getVersion(), ROUTER_AGENT_VERSION)) {
                return node;
            }
        }
        return null;
    }

    private Map<String, Object> owned(Long id) {
        if (id == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM proxy_exit WHERE id = ?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        if (!isAdmin() && !Objects.equals(((Number) row.get("user_id")).intValue(), currentUserId())) return null;
        return row;
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        Long id = ((Number) row.get("id")).longValue();
        item.put("id", id);
        item.put("name", row.get("name"));
        item.put("protocol", row.get("protocol"));
        item.put("server", row.get("server"));
        item.put("port", row.get("port"));
        item.put("ownerUserId", row.get("user_id"));
        item.put("ownerUserName", row.get("owner_name"));
        item.put("lastTestAt", row.get("last_test_at"));
        item.put("lastTestOk", row.get("last_test_ok") == null ? null : ((Number) row.get("last_test_ok")).intValue() == 1);
        item.put("lastTestIp", row.get("last_test_ip"));
        item.put("lastTestCountry", row.get("last_test_country"));
        item.put("lastTestLatency", row.get("last_test_latency"));
        item.put("lastTestError", WarpService.explainRuntimeError((String) row.get("last_test_error")));
        if (GROUP_TYPE.equals(row.get("protocol"))) {
            JSONObject group = outbound(row);
            List<Long> members = group.getJSONArray("members").toJavaList(Long.class);
            List<Map<String, Object>> memberViews = new ArrayList<>();
            for (Long member : members) memberViews.add(Map.of("id", member, "name", Objects.requireNonNullElse(exitName(member), "#" + member)));
            item.put("group", Map.of("members", memberViews, "direct", group.getBooleanValue("direct"), "warp", group.getBooleanValue("warp"),
                    "url", group.getString("url"), "intervalMinutes", group.getIntValue("intervalMinutes"), "tolerance", group.getIntValue("tolerance")));
        }
        item.put("usedBy", jdbc.queryForObject("SELECT COUNT(*) FROM private_proxy WHERE state <> 'deleted' AND (exit_custom_id = ? "
                + "OR route_rules LIKE ?)", Long.class, id, "%\"custom:" + id + "\"%"));
        return item;
    }

    private static String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    private static boolean isAdmin() {
        return Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
    }

    private static Integer currentUserId() {
        return JwtUtil.getUserIdFromToken();
    }
}
