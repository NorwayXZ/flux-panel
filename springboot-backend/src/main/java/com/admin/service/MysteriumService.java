package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PrivateProxyMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mysterium residential exits. Each node that needs one runs Mysterium's node software through
 * the Agent with its own account (one account per node, funded by the admin). A country in use
 * on a node is one connection to a residential provider behind a local HTTP proxy port, which
 * the node's routers use as the exit "mysterium:CC". Connections are checked and moved to
 * another provider when they fail; spending is tracked from the balance and capped per month.
 */
@Slf4j
@Service
public class MysteriumService {
    public static final String AGENT_VERSION = "2.83.0";
    static final String EXIT_PREFIX = "mysterium:";
    static final int PORT_BASE = 47100;
    static final String DISCOVERY = "https://discovery.mysterium.network/api/v4";
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final NodeMapper nodeMapper;
    private final PrivateProxyMapper proxyMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile List<Map<String, Object>> countryCache = List.of();
    private volatile long countryCacheAt;
    private volatile double mystUsd = 0.08;
    private volatile long mystUsdAt;

    public MysteriumService(JdbcTemplate jdbc, NodeMapper nodeMapper, PrivateProxyMapper proxyMapper) {
        this.jdbc = jdbc;
        this.nodeMapper = nodeMapper;
        this.proxyMapper = proxyMapper;
    }

    // ---- Settings ----

    public Map<String, Object> settings() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mysterium_setting WHERE id = 1");
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", row.get("enabled") != null && ((Number) row.get("enabled")).intValue() == 1);
        result.put("termsAgreedAt", row.get("terms_agreed_at"));
        result.put("termsAgreedBy", row.get("terms_agreed_by"));
        result.put("monthlyBudgetUsd", row.get("monthly_budget_usd") == null ? 20 : ((Number) row.get("monthly_budget_usd")).doubleValue());
        result.put("lowBalanceMyst", row.get("low_balance_myst") == null ? 10 : ((Number) row.get("low_balance_myst")).doubleValue());
        result.put("mystUsd", mystUsd());
        result.put("agentVersion", AGENT_VERSION);
        return result;
    }

    boolean enabled() {
        Map<String, Object> settings = settings();
        return Boolean.TRUE.equals(settings.get("enabled")) && settings.get("termsAgreedAt") != null;
    }

    public R saveSettings(Boolean enabled, Boolean agreeTerms, Double budgetUsd, Double lowBalance) {
        Map<String, Object> current = settings();
        boolean agreed = current.get("termsAgreedAt") != null || Boolean.TRUE.equals(agreeTerms);
        if (Boolean.TRUE.equals(enabled) && !agreed) return R.err("启用前需要同意 Mysterium 用户条款");
        if (budgetUsd != null && (budgetUsd < 0 || budgetUsd > 100000)) return R.err("每月预算不正确");
        if (lowBalance != null && (lowBalance < 0 || lowBalance > 100000)) return R.err("余额提醒额度不正确");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO mysterium_setting (id, enabled, terms_agreed_at, terms_agreed_by, monthly_budget_usd, low_balance_myst) "
                        + "VALUES (1, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled), terms_agreed_at=VALUES(terms_agreed_at), "
                        + "terms_agreed_by=VALUES(terms_agreed_by), monthly_budget_usd=VALUES(monthly_budget_usd), low_balance_myst=VALUES(low_balance_myst)",
                Boolean.TRUE.equals(enabled != null ? enabled : current.get("enabled")) ? 1 : 0,
                current.get("termsAgreedAt") != null ? current.get("termsAgreedAt") : Boolean.TRUE.equals(agreeTerms) ? now : null,
                current.get("termsAgreedBy") != null ? current.get("termsAgreedBy") : Boolean.TRUE.equals(agreeTerms) ? currentUser() : null,
                budgetUsd != null ? budgetUsd : current.get("monthlyBudgetUsd"),
                lowBalance != null ? lowBalance : current.get("lowBalanceMyst"));
        // A higher budget lifts this month's suspension at the next check.
        if (budgetUsd != null) checkBudgets();
        return R.ok(settings());
    }

    // ---- Exit keys ----

    public static boolean isExit(String exit) {
        return exit != null && exit.matches("mysterium:[A-Z]{2}");
    }

    public static String tag(String country) {
        return "mys-" + country.toLowerCase(Locale.ROOT);
    }

    /** Countries a proxy's routing sends to Mysterium. */
    public static Set<String> countries(String exitType, String exitCountry, List<JSONObject> rules) {
        Set<String> countries = new TreeSet<>();
        if ("mysterium".equals(exitType) && StringUtils.isNotBlank(exitCountry)) countries.add(exitCountry.toUpperCase(Locale.ROOT));
        for (JSONObject rule : rules) {
            String exit = rule.getString("exit");
            if (isExit(exit)) countries.add(exit.substring(EXIT_PREFIX.length()));
        }
        return countries;
    }

    /** Router outbounds for the node's Mysterium proxies in the given countries, connecting them when needed. */
    public Map<String, Integer> ensureCountries(Node node, Set<String> countries) {
        Map<String, Integer> ports = new LinkedHashMap<>();
        for (String country : countries) ports.put(country, ensureCountry(node, country));
        return ports;
    }

    static JSONObject outbound(String country, int port) {
        JSONObject outbound = new JSONObject(true);
        outbound.put("type", "http");
        outbound.put("tag", tag(country));
        outbound.put("server", "127.0.0.1");
        outbound.put("server_port", port);
        return outbound;
    }

    // ---- Accounts and connections ----

    /** The node's connection to the country, opened now if there is none. Returns the local proxy port. */
    synchronized int ensureCountry(Node node, String country) {
        if (!enabled()) throw new IllegalStateException("Mysterium 家宽还没有启用，请先在“出口管理 → Mysterium 家宽”里启用");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), AGENT_VERSION)) {
            throw new IllegalStateException("Mysterium 家宽需要节点 Agent " + AGENT_VERSION + " 或更高版本，请先升级该节点");
        }
        Map<String, Object> account = account(node.getId());
        if (account == null) {
            R setup = setupNode(node);
            if (setup.getCode() != 0) throw new IllegalStateException(setup.getMsg());
            account = account(node.getId());
        }
        if (account != null && intValue(account.get("suspended")) == 1) {
            throw new IllegalStateException("节点“" + node.getName() + "”本月的 Mysterium 花费已达上限，已暂停使用");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mysterium_connection WHERE node_id = ? AND country = ?", node.getId(), country);
        if (!rows.isEmpty() && "connected".equals(rows.get(0).get("state"))) return intValue(rows.get(0).get("proxy_port"));
        if (account != null && decimal(account.get("balance")) <= 0) {
            throw new IllegalStateException("节点“" + node.getName() + "”的 Mysterium 账户余额为 0，请先充值");
        }
        int port = rows.isEmpty() ? allocatePort(node.getId()) : intValue(rows.get(0).get("proxy_port"));
        String excluded = rows.isEmpty() ? null : (String) rows.get(0).get("excluded");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO mysterium_connection (node_id, country, proxy_port, state, fail_count, created_time) VALUES (?,?,?,?,0,?) "
                + "ON DUPLICATE KEY UPDATE state=VALUES(state)", node.getId(), country, port, "connecting", now);
        JSONObject result = connect(node, country, port, excluded);
        if (result == null) {
            String error = (String) jdbc.queryForList("SELECT last_error FROM mysterium_connection WHERE node_id=? AND country=?", node.getId(), country)
                    .get(0).get("last_error");
            throw new IllegalStateException("连接 Mysterium " + country + " 家宽失败：" + error);
        }
        return port;
    }

    /** Connects (or moves) the connection; returns the agent's result, or null after recording the error. */
    private synchronized JSONObject connect(Node node, String country, int port, String excluded) {
        return connect(node, country, port, excluded, "连接 / 自动恢复");
    }

    private synchronized JSONObject connect(Node node, String country, int port, String excluded, String reason) {
        return connect(node, country, port, excluded, reason, null);
    }

    private synchronized JSONObject connect(Node node, String country, int port, String excluded, String reason, String fallbackProvider) {
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT exit_ip, provider_id FROM mysterium_connection WHERE node_id=? AND country=?", node.getId(), country);
        String oldIp = previous.isEmpty() ? null : (String) previous.get(0).get("exit_ip");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("proxyPort", port);
        request.put("country", country);
        request.put("excludeProviders", splitExcluded(excluded));
        if (StringUtils.isNotBlank(fallbackProvider)) request.put("fallbackProvider", fallbackProvider);
        GostDto response = sendConnect(node.getId(), request);
        long now = System.currentTimeMillis();
        if (response == null || !"OK".equals(response.getMsg())) {
            String error = response == null ? "节点无响应" : response.getMsg();
            jdbc.update("UPDATE mysterium_connection SET state='failed', last_error=?, last_check_at=? WHERE node_id=? AND country=?",
                    truncate(error), now, node.getId(), country);
            recordEvent(node.getId(), country, reason + "失败：" + error, oldIp, null);
            return null;
        }
        JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
        jdbc.update("UPDATE mysterium_connection SET state='connected', provider_id=?, exit_ip=?, city=?, isp=?, latency_ms=?, fail_count=0, "
                        + "last_check_at=?, last_ok_at=?, last_error=NULL WHERE node_id=? AND country=?",
                data.getString("providerId"), data.getString("exitIp"), StringUtils.left(data.getString("city"), 100),
                StringUtils.left(data.getString("isp"), 200), data.getInteger("latencyMs"), now, now, node.getId(), country);
        if (data.getBooleanValue("fallback")) {
            // The old provider works again, so it no longer belongs on the exclusion list.
            List<String> list = splitExcluded(excluded);
            list.remove(data.getString("providerId"));
            jdbc.update("UPDATE mysterium_connection SET excluded=? WHERE node_id=? AND country=?", String.join(",", list), node.getId(), country);
            recordEvent(node.getId(), country, reason + "失败，没有连上新的家宽节点，已恢复原来的出口 " + data.getString("exitIp"), oldIp, data.getString("exitIp"));
            return data;
        }
        recordEvent(node.getId(), country, reason + "：" + StringUtils.defaultIfBlank(oldIp, "未连接") + " → " + data.getString("exitIp"), oldIp, data.getString("exitIp"));
        return data;
    }

    GostDto sendConnect(Long nodeId, Map<String, Object> request) {
        return WebSocketServer.send_msg(nodeId, request, "MysteriumConnect", 150);
    }

    boolean nodeOnline(Long nodeId) { return WebSocketServer.isNodeOnline(nodeId); }

    private void recordEvent(Long nodeId, String country, String message, String oldIp, String newIp) {
        jdbc.update("INSERT INTO mysterium_event (node_id,country,message,old_ip,new_ip,created_time) VALUES (?,?,?,?,?,?)",
                nodeId, country, truncate(message), oldIp, newIp, System.currentTimeMillis());
    }

    public R events(Long nodeId, String country) {
        if (nodeId == null || country == null || !country.matches("[A-Z]{2}")) return R.err("节点或国家不正确");
        return R.ok(jdbc.queryForList("SELECT id,message,old_ip AS oldIp,new_ip AS newIp,created_time AS createdTime FROM mysterium_event WHERE node_id=? AND country=? ORDER BY id DESC LIMIT 100", nodeId, country));
    }

    /** Card view shared by proxies using this node and country. */
    public Map<String, Object> exitView(Long nodeId, String country) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("country", country);
        view.put("countryName", country);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mysterium_connection WHERE node_id=? AND country=?", nodeId, country);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, Object> account = account(nodeId);
        boolean suspended = account != null && intValue(account.get("suspended")) == 1;
        view.put("status", suspended ? "no_exit" : "connected".equals(row.get("state")) ? "ok" : "connecting".equals(row.get("state")) ? "searching" : "no_exit");
        view.put("exitIp", row.get("exit_ip"));
        view.put("exitOwner", row.get("isp"));
        view.put("detail", suspended ? "本月预算已达上限" : row.get("last_error"));
        view.put("checkedAt", row.get("last_check_at"));
        List<Map<String, Object>> times = jdbc.queryForList("SELECT MAX(created_time) AS active_since FROM mysterium_event WHERE node_id=? AND country=? AND new_ip IS NOT NULL", nodeId, country);
        view.put("activeSince", times.isEmpty() ? row.get("created_time") : times.get(0).get("active_since"));
        return view;
    }

    static List<String> splitExcluded(String excluded) {
        List<String> list = new ArrayList<>();
        if (StringUtils.isBlank(excluded)) return list;
        for (String id : excluded.split(",")) if (!id.isBlank()) list.add(id.trim());
        return list;
    }

    /** Adds a provider to the exclusion list, keeping the most recent ten. */
    static String addExcluded(String excluded, String provider) {
        List<String> list = splitExcluded(excluded);
        if (StringUtils.isNotBlank(provider)) {
            list.remove(provider);
            list.add(provider);
        }
        while (list.size() > 10) list.remove(0);
        return String.join(",", list);
    }

    private int allocatePort(Long nodeId) {
        Set<Integer> used = new LinkedHashSet<>(jdbc.queryForList("SELECT proxy_port FROM mysterium_connection WHERE node_id = ?", Integer.class, nodeId));
        for (int port = PORT_BASE; port < PORT_BASE + 400; port++) if (!used.contains(port)) return port;
        throw new IllegalStateException("没有可用的本地端口");
    }

    private Long sharedOwner(Long nodeId) {
        if (nodeId == null) return null;
        // The shared tables are initialized separately so older installations
        // remain usable before the first shared-account setup.
        try {
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT p.owner_node_id FROM mysterium_shared_pool p JOIN mysterium_shared_member m ON m.node_id=? AND m.state='ready' WHERE p.id=1 AND p.enabled=1", nodeId);
            return rows.isEmpty() ? nodeId : ((Number)rows.get(0).get("owner_node_id")).longValue();
        } catch (org.springframework.dao.DataAccessException e) { return nodeId; }
    }

    private Map<String, Object> account(Long nodeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mysterium_account WHERE node_id = ?", nodeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Starts Mysterium on the node and creates (or takes over) its account. */
    public R setupNode(Node node) {
        if (!enabled()) return R.err("Mysterium 家宽还没有启用");
        if (!WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), AGENT_VERSION)) {
            return R.err("Mysterium 家宽需要节点 Agent " + AGENT_VERSION + " 或更高版本，请先升级该节点");
        }
        GostDto response = WebSocketServer.send_msg(node.getId(), Map.of("agreeTerms", true), "MysteriumSetup", 240);
        if (response == null || !"OK".equals(response.getMsg())) {
            return R.err("在节点上启动 Mysterium 失败：" + (response == null ? "节点无响应" : response.getMsg()));
        }
        JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
        long now = System.currentTimeMillis();
        double balance = data.getDoubleValue("balance");
        jdbc.update("INSERT INTO mysterium_account (node_id, identity, registration, channel_address, balance, last_balance, month, month_spent, "
                        + "suspended, last_status_at, created_time) VALUES (?,?,?,?,?,?,?,0,0,?,?) ON DUPLICATE KEY UPDATE identity=VALUES(identity), "
                        + "registration=VALUES(registration), channel_address=VALUES(channel_address), balance=VALUES(balance), last_status_at=VALUES(last_status_at)",
                node.getId(), data.getString("identity"), data.getString("registrationStatus"), data.getString("channelAddress"),
                balance, balance, currentMonth(), now, now);
        return R.ok(accountView(account(node.getId())));
    }

    public R setup(Long nodeId) {
        Node node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        if (node == null) return R.err("节点不存在");
        return setupNode(node);
    }

    public R topup(Long nodeId, String gateway, Double amountUsd, String country) {
        nodeId = sharedOwner(nodeId);
        Node node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        if (node == null || account(nodeId) == null) return R.err("这个节点还没有启用 Mysterium");
        if (!Set.of("paypal", "stripe", "coingate").contains(gateway)) return R.err("不支持的付款方式");
        double minimum = "coingate".equals(gateway) ? 10 : 6.3;
        if (amountUsd == null || amountUsd < minimum || amountUsd > 1000) return R.err("充值金额需要在 " + minimum + " 到 1000 美元之间");
        String code = StringUtils.defaultIfBlank(country, "CN").toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{2}")) return R.err("国家代码不正确");
        Map<String, Object> request = Map.of("gateway", gateway, "amountUsd", String.format(Locale.ROOT, "%.2f", amountUsd), "country", code);
        GostDto response = WebSocketServer.send_msg(nodeId, request, "MysteriumTopup", 60);
        if (response == null || !"OK".equals(response.getMsg())) {
            return R.err("创建充值订单失败：" + (response == null ? "节点无响应" : response.getMsg()));
        }
        return R.ok(response.getData());
    }

    /** Re-reads the node's balance and connections; returns the account view. */
    public R refresh(Long nodeId) {
        Node node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        if (node == null || account(nodeId) == null) return R.err("这个节点还没有启用 Mysterium");
        String error = refreshAccount(node);
        if (error != null) return R.err(error);
        checkBudgets();
        return R.ok(accountView(account(nodeId)));
    }

    /** Moves the country's connection to another provider now. */
    public R reconnect(Long nodeId, String country) {
        return reconnect(nodeId, country, "手动切换");
    }

    public synchronized R reconnect(Long nodeId, String country, String reason) {
        if (nodeId == null || country == null || !country.matches("[A-Z]{2}")) return R.err("节点或国家不正确");
        if (!enabled()) return R.err("Mysterium 家宽还没有启用");
        Map<String, Object> account = nodeId == null ? null : account(nodeId);
        if (account == null) return R.err("节点尚未启用 Mysterium");
        if (intValue(account.get("suspended")) == 1) return R.err("本月预算已达上限，已暂停使用");
        if (decimal(account.get("balance")) <= 0) return R.err("Mysterium 余额不足，请先充值");
        Node node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mysterium_connection WHERE node_id=? AND country=?", nodeId, country);
        if (node == null || rows.isEmpty()) return R.err("连接不存在");
        if (!nodeOnline(nodeId)) return R.err("节点离线");
        Map<String, Object> row = rows.get(0);
        String excluded = addExcluded((String) row.get("excluded"), (String) row.get("provider_id"));
        jdbc.update("UPDATE mysterium_connection SET excluded=? WHERE node_id=? AND country=?", excluded, nodeId, country);
        // A switch keeps the working provider as the last resort instead of leaving the proxy without an exit.
        JSONObject result = connect(node, country, intValue(row.get("proxy_port")), excluded, reason, (String) row.get("provider_id"));
        if (result == null) return R.err("换节点失败，请稍后再试");
        if (result.getBooleanValue("fallback")) {
            return R.err("没有连上新的家宽节点，已恢复原来的出口 " + StringUtils.defaultString(result.getString("exitIp")) + "，请稍后再试");
        }
        return R.ok(result);
    }

    private String refreshAccount(Node node) {
        if (!WebSocketServer.isNodeOnline(node.getId())) return "节点离线";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT country, proxy_port FROM mysterium_connection WHERE node_id = ?", node.getId());
        List<Map<String, Object>> connections = new ArrayList<>();
        for (Map<String, Object> row : rows) connections.add(Map.of("proxyPort", intValue(row.get("proxy_port")), "country", row.get("country")));
        GostDto response = WebSocketServer.send_msg(node.getId(), Map.of("connections", connections), "MysteriumStatus", 60);
        if (response == null || !"OK".equals(response.getMsg())) {
            String error = response == null ? "节点无响应" : response.getMsg();
            jdbc.update("UPDATE mysterium_account SET last_error=? WHERE node_id=?", truncate(error), node.getId());
            return error;
        }
        JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
        Map<String, Object> account = account(node.getId());
        double balance = data.getDoubleValue("balance");
        boolean sharedMember = !Objects.equals(sharedOwner(node.getId()), node.getId());
        Spend spend = sharedMember ? new Spend(currentMonth(), 0) : spend(account == null ? balance : decimal(account.get("last_balance")), balance,
                account == null ? currentMonth() : (String) account.get("month"), account == null ? 0 : decimal(account.get("month_spent")), currentMonth());
        jdbc.update("UPDATE mysterium_account SET identity=?, registration=?, channel_address=?, balance=?, last_balance=?, month=?, month_spent=?, "
                        + "last_status_at=?, last_error=NULL WHERE node_id=?", data.getString("identity"), data.getString("registrationStatus"),
                data.getString("channelAddress"), balance, balance, spend.month, spend.monthSpent, System.currentTimeMillis(), node.getId());
        JSONArray list = data.getJSONArray("connections");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                JSONObject item = list.getJSONObject(i);
                jdbc.update("UPDATE mysterium_connection SET bytes_received=?, bytes_sent=? WHERE node_id=? AND country=?",
                        item.getLongValue("bytesReceived"), item.getLongValue("bytesSent"), node.getId(), item.getString("country"));
            }
        }
        return null;
    }

    record Spend(String month, double monthSpent) {}

    /**
     * Spending from two balance readings: a drop is spending, a rise is a top-up (not spending).
     * A new month starts from zero.
     */
    static Spend spend(double previousBalance, double balance, String month, double monthSpent, String currentMonth) {
        double spent = Objects.equals(month, currentMonth) ? monthSpent : 0;
        if (balance < previousBalance) spent += previousBalance - balance;
        return new Spend(currentMonth, BigDecimal.valueOf(spent).setScale(6, RoundingMode.HALF_UP).doubleValue());
    }

    // ---- Scheduled upkeep ----

    /** Every two minutes: checks connections in use, moves failing ones, and closes unused ones. */
    @Scheduled(fixedDelay = 120_000L, initialDelay = 180_000L)
    public void upkeep() {
        try {
            Map<Long, Set<String>> used = usage();
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM mysterium_connection")) {
                Long nodeId = ((Number) row.get("node_id")).longValue();
                String country = (String) row.get("country");
                Node node = nodeMapper.selectById(nodeId);
                if (node == null) {
                    jdbc.update("DELETE FROM mysterium_connection WHERE node_id=? AND country=?", nodeId, country);
                    continue;
                }
                if (!WebSocketServer.isNodeOnline(nodeId)) continue;
                int port = intValue(row.get("proxy_port"));
                if (!used.getOrDefault(nodeId, Set.of()).contains(country)) {
                    WebSocketServer.send_msg(nodeId, Map.of("proxyPort", port), "MysteriumDisconnect", 30);
                    jdbc.update("DELETE FROM mysterium_connection WHERE node_id=? AND country=?", nodeId, country);
                    continue;
                }
                if ("suspended".equals(row.get("state"))) continue;
                checkConnection(node, row);
            }
        } catch (RuntimeException e) {
            log.warn("Mysterium upkeep skipped: {}", e.getMessage());
        }
    }

    private void checkConnection(Node node, Map<String, Object> row) {
        String country = (String) row.get("country");
        int port = intValue(row.get("proxy_port"));
        long now = System.currentTimeMillis();
        GostDto response = WebSocketServer.send_msg(node.getId(), Map.of("proxyPort", port), "MysteriumCheck", 30);
        if (response != null && "OK".equals(response.getMsg())) {
            JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
            jdbc.update("UPDATE mysterium_connection SET state='connected', fail_count=0, exit_ip=?, latency_ms=?, last_check_at=?, last_ok_at=?, "
                    + "last_error=NULL WHERE node_id=? AND country=?", data.getString("exitIp"), data.getInteger("latencyMs"), now, now, node.getId(), country);
            return;
        }
        int failures = intValue(row.get("fail_count")) + 1;
        String error = response == null ? "节点无响应" : response.getMsg();
        jdbc.update("UPDATE mysterium_connection SET fail_count=?, last_check_at=?, last_error=? WHERE node_id=? AND country=?",
                failures, now, truncate(error), node.getId(), country);
        if (failures < 2 && "connected".equals(row.get("state"))) return;
        // Two failures in a row (or already failed): move to another residential provider on the same port.
        String excluded = addExcluded((String) row.get("excluded"), (String) row.get("provider_id"));
        jdbc.update("UPDATE mysterium_connection SET excluded=? WHERE node_id=? AND country=?", excluded, node.getId(), country);
        if (connect(node, country, port, excluded) != null) {
            log.info("Mysterium {} on node {} moved to another provider", country, node.getId());
        } else {
            jdbc.update("UPDATE mysterium_connection SET fail_count=? WHERE node_id=? AND country=?", failures, node.getId(), country);
        }
    }

    /** Every ten minutes: balances, monthly spending and the budget cap. */
    @Scheduled(fixedDelay = 600_000L, initialDelay = 240_000L)
    public void accounting() {
        try {
            for (Map<String, Object> row : jdbc.queryForList("SELECT node_id FROM mysterium_account")) {
                Node node = nodeMapper.selectById(((Number) row.get("node_id")).longValue());
                if (node != null && WebSocketServer.isNodeOnline(node.getId())) refreshAccount(node);
            }
            checkBudgets();
        } catch (RuntimeException e) {
            log.warn("Mysterium accounting skipped: {}", e.getMessage());
        }
    }

    /** Suspends nodes over this month's budget and resumes nodes back under it. */
    void checkBudgets() {
        double budget = ((Number) settings().get("monthlyBudgetUsd")).doubleValue();
        double rate = mystUsd();
        String month = currentMonth();
        for (Map<String, Object> account : jdbc.queryForList("SELECT * FROM mysterium_account")) {
            Long nodeId = ((Number) account.get("node_id")).longValue();
            Map<String,Object> budgetAccount = account(sharedOwner(nodeId));
            if (budgetAccount == null) budgetAccount = account;
            double spentUsd = Objects.equals(budgetAccount.get("month"), month) ? decimal(budgetAccount.get("month_spent")) * rate : 0;
            boolean over = budget > 0 && spentUsd >= budget;
            boolean suspended = intValue(account.get("suspended")) == 1;
            if (over && !suspended) {
                jdbc.update("UPDATE mysterium_account SET suspended=1 WHERE node_id=?", nodeId);
                for (Map<String, Object> connection : jdbc.queryForList("SELECT proxy_port, country FROM mysterium_connection WHERE node_id=?", nodeId)) {
                    if (WebSocketServer.isNodeOnline(nodeId)) {
                        WebSocketServer.send_msg(nodeId, Map.of("proxyPort", intValue(connection.get("proxy_port"))), "MysteriumDisconnect", 30);
                    }
                }
                jdbc.update("UPDATE mysterium_connection SET state='suspended', last_error='本月花费已达上限' WHERE node_id=?", nodeId);
                log.info("Mysterium on node {} suspended: spent {} USD this month", nodeId, spentUsd);
            } else if (!over && suspended) {
                jdbc.update("UPDATE mysterium_account SET suspended=0 WHERE node_id=?", nodeId);
                // Failed connections are brought back by the next upkeep.
                jdbc.update("UPDATE mysterium_connection SET state='failed', fail_count=2, last_error=NULL WHERE node_id=?", nodeId);
            }
        }
    }

    /** Countries each node's running proxies send to Mysterium. */
    private Map<Long, Set<String>> usage() {
        Map<Long, Set<String>> used = new HashMap<>();
        for (PrivateProxy proxy : proxyMapper.selectList(new QueryWrapper<PrivateProxy>().eq("state", "active"))) {
            Set<String> countries = countries(proxy.getExitType(), proxy.getExitCountry(), ProxyRoutingService.rules(proxy.getRouteRules()));
            if (!countries.isEmpty()) used.computeIfAbsent(proxy.getNodeId(), key -> new TreeSet<>()).addAll(countries);
        }
        return used;
    }

    // ---- Views ----

    public R overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", settings());
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM mysterium_account ORDER BY node_id")) accounts.add(accountView(row));
        result.put("accounts", accounts);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Node node : nodeMapper.selectList(null)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.getId());
            item.put("name", node.getName());
            item.put("online", WebSocketServer.isNodeOnline(node.getId()));
            item.put("supported", AgentVersionUtil.isAtLeast(node.getVersion(), AGENT_VERSION));
            nodes.add(item);
        }
        result.put("nodes", nodes);
        return R.ok(result);
    }

    private Map<String, Object> accountView(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (row == null) return item;
        Long nodeId = ((Number) row.get("node_id")).longValue();
        Node node = nodeMapper.selectById(nodeId);
        double rate = mystUsd();
        double balance = decimal(row.get("balance"));
        double spent = Objects.equals(row.get("month"), currentMonth()) ? decimal(row.get("month_spent")) : 0;
        item.put("nodeId", nodeId);
        item.put("nodeName", node == null ? "#" + nodeId : node.getName());
        Long billingOwner = sharedOwner(nodeId);
        item.put("billingOwnerNodeId", billingOwner);
        item.put("sharedBilling", !Objects.equals(billingOwner, nodeId));
        item.put("identity", row.get("identity"));
        item.put("registration", row.get("registration"));
        item.put("channelAddress", row.get("channel_address"));
        item.put("balance", balance);
        item.put("balanceUsd", round(balance * rate));
        item.put("monthSpent", spent);
        item.put("monthSpentUsd", round(spent * rate));
        item.put("suspended", intValue(row.get("suspended")) == 1);
        item.put("lastStatusAt", row.get("last_status_at"));
        item.put("lastError", row.get("last_error"));
        List<Map<String, Object>> connections = new ArrayList<>();
        for (Map<String, Object> connection : jdbc.queryForList("SELECT * FROM mysterium_connection WHERE node_id=? ORDER BY country", nodeId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("country", connection.get("country"));
            view.put("proxyPort", connection.get("proxy_port"));
            view.put("state", connection.get("state"));
            view.put("exitIp", connection.get("exit_ip"));
            view.put("city", connection.get("city"));
            view.put("isp", connection.get("isp"));
            view.put("latencyMs", connection.get("latency_ms"));
            view.put("lastOkAt", connection.get("last_ok_at"));
            view.put("lastError", connection.get("last_error"));
            view.put("bytes", longValue(connection.get("bytes_received")) + longValue(connection.get("bytes_sent")));
            connections.add(view);
        }
        item.put("connections", connections);
        return item;
    }

    /** Countries with residential providers, from Mysterium's public discovery (cached for an hour). */
    public R countryList() {
        long now = System.currentTimeMillis();
        if (now - countryCacheAt > 3_600_000L || countryCache.isEmpty()) {
            try {
                countryCache = loadCountries();
                countryCacheAt = now;
            } catch (RuntimeException e) {
                if (countryCache.isEmpty()) return R.err("读取 Mysterium 节点列表失败：" + e.getMessage());
            }
        }
        return R.ok(countryCache);
    }

    private List<Map<String, Object>> loadCountries() {
        JSONArray proposals = JSON.parseArray(get(DISCOVERY + "/proposals?service_type=wireguard&ip_type=residential"));
        Map<String, int[]> counts = new HashMap<>();
        for (int i = 0; i < proposals.size(); i++) {
            JSONObject proposal = proposals.getJSONObject(i);
            JSONObject location = proposal.getJSONObject("location");
            if (location == null || !"residential".equals(location.getString("ip_type"))) continue;
            int[] count = counts.computeIfAbsent(location.getString("country"), key -> new int[2]);
            count[0]++;
            JSONObject quality = proposal.getJSONObject("quality");
            if (quality != null && quality.getDoubleValue("quality") >= 2 && quality.getDoubleValue("bandwidth") >= 20) count[1]++;
        }
        JSONObject prices = JSON.parseObject(get(DISCOVERY + "/prices"));
        double rate = mystUsd();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            if (entry.getValue()[1] < 3 || entry.getKey() == null) continue;
            double perGib = pricePerGib(prices, entry.getKey());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("country", entry.getKey());
            item.put("nodes", entry.getValue()[0]);
            item.put("goodNodes", entry.getValue()[1]);
            item.put("priceMystPerGib", round(perGib));
            item.put("priceUsdPerGib", round(perGib * rate));
            result.add(item);
        }
        result.sort((a, b) -> Integer.compare((Integer) b.get("goodNodes"), (Integer) a.get("goodNodes")));
        return result;
    }

    static double pricePerGib(JSONObject prices, String country) {
        JSONObject perCountry = prices.getJSONObject("per_country");
        JSONObject source = perCountry != null && perCountry.containsKey(country) ? perCountry.getJSONObject(country) : prices.getJSONObject("defaults");
        try {
            return source.getJSONObject("current").getJSONObject("residential").getJSONObject("wireguard").getDoubleValue("price_per_gib_human_readable");
        } catch (RuntimeException e) {
            return 0;
        }
    }

    double mystUsd() {
        long now = System.currentTimeMillis();
        if (now - mystUsdAt > 1_800_000L) {
            mystUsdAt = now;
            try {
                JSONObject json = JSON.parseObject(get("https://api.coingecko.com/api/v3/simple/price?ids=mysterium&vs_currencies=usd"));
                double value = json.getJSONObject("mysterium").getDoubleValue("usd");
                if (value > 0) mystUsd = value;
            } catch (RuntimeException e) {
                log.debug("MYST price unavailable: {}", e.getMessage());
            }
        }
        return mystUsd;
    }

    private String get(String url) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "CloudNest-Panel").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            return response.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e.getClass().getSimpleName());
        }
    }

    static String currentMonth() {
        return YearMonth.now(ZONE).toString();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static double decimal(Object value) {
        return value == null ? 0 : ((Number) value).doubleValue();
    }

    private static int intValue(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static long longValue(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String currentUser() {
        Integer id = JwtUtil.getUserIdFromToken();
        return id == null ? null : String.valueOf(id);
    }
}
