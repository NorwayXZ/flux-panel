package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.DomainRouteCreateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class DockerAppCenterService {
    public static final String MIN_AGENT_VERSION = "2.47.0";
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{1,60}$");

    private final JdbcTemplate jdbcTemplate;
    private final NodeMapper nodeMapper;
    private final ServicePublishingService servicePublishingService;

    public DockerAppCenterService(JdbcTemplate jdbcTemplate, NodeMapper nodeMapper,
                                  ServicePublishingService servicePublishingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.nodeMapper = nodeMapper;
        this.servicePublishingService = servicePublishingService;
    }

    public R overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = jdbcTemplate.queryForList(
                "SELECT id,name,server_ip AS serverIp,ip,status,version FROM node ORDER BY name,id");
        for (Map<String, Object> node : nodes) {
            Long nodeId = longValue(node.get("id"));
            node.put("online", nodeId != null && WebSocketServer.isNodeOnline(nodeId));
            node.put("compatible", AgentVersionUtil.isAtLeast(Objects.toString(node.get("version"), ""), MIN_AGENT_VERSION));
        }
        List<Map<String, Object>> apps = jdbcTemplate.queryForList(
                "SELECT a.id,a.user_id AS userId,a.node_id AS nodeId,n.name AS nodeName,a.template_id AS templateId,"
                        + "a.name,a.container_name AS containerName,a.image,a.version_label AS versionLabel,a.host_port AS hostPort,"
                        + "a.container_port AS containerPort,a.domain_route_id AS domainRouteId,r.domain AS domain,"
                        + "a.state,a.last_error AS lastError,a.last_command AS lastCommand,a.rollback_command AS rollbackCommand,"
                        + "a.compose_path AS composePath,a.backup_path AS backupPath,a.detected,a.created_time AS createdTime,a.updated_time AS updatedTime "
                        + "FROM docker_app_instance a LEFT JOIN node n ON n.id=a.node_id LEFT JOIN domain_route r ON r.id=a.domain_route_id "
                        + "WHERE a.state<>'deleted' ORDER BY a.updated_time DESC,a.id DESC");
        for (Map<String, Object> app : apps) {
            Long nodeId = longValue(app.get("nodeId"));
            app.put("nodeOnline", nodeId != null && WebSocketServer.isNodeOnline(nodeId));
            app.put("detected", truth(app.get("detected")));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("apps", apps.size());
        summary.put("active", apps.stream().filter(item -> "active".equals(item.get("state"))).count());
        summary.put("errors", apps.stream().filter(item -> "error".equals(item.get("state"))).count());
        summary.put("dockerReadyNodes", nodes.stream().filter(item -> truth(item.get("online")) && truth(item.get("compatible"))).count());
        result.put("nodes", nodes);
        result.put("templates", templates());
        result.put("apps", apps);
        result.put("summary", summary);
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        return R.ok(result);
    }

    public R inspect(Long nodeId) {
        Node node = nodeMapper.selectById(nodeId);
        if (node == null) return R.err("节点不存在");
        if (!WebSocketServer.isNodeOnline(nodeId)) return R.err("节点离线，无法检查 Docker");
        GostDto response = WebSocketServer.send_msg(nodeId, Map.of("limit", 120), "DockerAppInspect", 20);
        if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
            return R.err("Docker 检查失败：" + (response == null ? "Agent 无响应" : response.getMsg()));
        }
        JSONObject payload = JSONObject.parseObject(JSON.toJSONString(response.getData()));
        payload.put("nodeId", node.getId());
        payload.put("nodeName", node.getName());
        payload.put("minimumAgentVersion", MIN_AGENT_VERSION);
        return R.ok(payload);
    }

    public R deploy(Map<String, Object> params) {
        try {
            String templateId = required(params, "templateId");
            Template template = template(templateId);
            long nodeId = Long.parseLong(required(params, "nodeId"));
            Node node = requireNode(nodeId);
            String name = cleanName(Objects.toString(params.getOrDefault("name", template.name())));
            String containerName = cleanContainerName(Objects.toString(params.getOrDefault("containerName", "flux-" + template.id() + "-" + nodeId)));
            int hostPort = intValue(params.get("hostPort"), template.defaultHostPort());
            if (hostPort <= 0) hostPort = nextPort(nodeId, template.defaultHostPort());
            ensurePortFree(nodeId, hostPort, null);

            Map<String, Object> payload = buildPayload(template, name, containerName, hostPort);
            long now = System.currentTimeMillis();
            String command = manualCommand(payload, "deploy");
            jdbcTemplate.update("INSERT INTO docker_app_instance "
                            + "(user_id,node_id,template_id,name,container_name,image,version_label,host_port,container_port,state,last_command,rollback_command,created_time,updated_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,'provisioning',?,?,?,?)",
                    JwtUtil.getUserIdFromToken(), nodeId, template.id(), name, containerName, template.image(), template.versionLabel(),
                    hostPort, template.containerPort(), command, manualCommand(payload, "remove"), now, now);
            Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            event(id, nodeId, "deploy", "pending", "应用已保存，准备下发 Docker 部署");
            R applied = apply(id, node, payload, "DockerAppDeploy", "deploy");
            if (truth(params.get("bindDomain")) && isActive(id)) {
                bindDomainRoute(id, nodeId, name, hostPort, params);
                return overview();
            }
            return applied;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    public R action(Long id, String action) {
        Map<String, Object> app = app(id);
        if (app == null) return R.err("应用不存在");
        long nodeId = Objects.requireNonNull(longValue(app.get("node_id")));
        Node node = requireNode(nodeId);
        String normalized = normalizeAction(action);
        Template template = template(Objects.toString(app.get("template_id")));
        Map<String, Object> payload = buildPayload(template, Objects.toString(app.get("name")),
                Objects.toString(app.get("container_name")), intValue(app.get("host_port"), template.defaultHostPort()));
        payload.put("appId", id);
        jdbcTemplate.update("UPDATE docker_app_instance SET state=?,last_command=?,updated_time=? WHERE id=?",
                "remove".equals(normalized) ? "delete_pending" : "operating", manualCommand(payload, normalized), System.currentTimeMillis(), id);
        event(id, nodeId, normalized, "pending", "已下发 " + actionLabel(normalized) + " 操作");
        return apply(id, node, payload, "DockerAppAction", normalized);
    }

    public R command(Long id, String action) {
        Map<String, Object> app = app(id);
        if (app == null) return R.err("应用不存在");
        Template template = template(Objects.toString(app.get("template_id")));
        Map<String, Object> payload = buildPayload(template, Objects.toString(app.get("name")),
                Objects.toString(app.get("container_name")), intValue(app.get("host_port"), template.defaultHostPort()));
        payload.put("appId", id);
        return R.ok(manualCommand(payload, normalizeAction(action)));
    }

    public R events(Long id) {
        return R.ok(jdbcTemplate.queryForList("SELECT id,app_id AS appId,node_id AS nodeId,event_type AS eventType,status,detail,created_time AS createdTime "
                + "FROM docker_app_event WHERE app_id=? ORDER BY created_time DESC,id DESC LIMIT 80", id));
    }

    private R apply(Long id, Node node, Map<String, Object> payload, String commandType, String action) {
        if (!WebSocketServer.isNodeOnline(node.getId())) {
            markError(id, node.getId(), action, "节点离线，已保留手动命令");
            return overview();
        }
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            markError(id, node.getId(), action, "Agent 低于 " + MIN_AGENT_VERSION + "，请先升级 Agent 或使用手动命令");
            return overview();
        }
        payload.put("action", action);
        GostDto response = WebSocketServer.send_msg(node.getId(), payload, commandType, 120);
        if (response == null || !"OK".equals(response.getMsg())) {
            markError(id, node.getId(), action, response == null ? "Agent 无响应" : response.getMsg());
            return overview();
        }
        JSONObject data = response.getData() == null ? new JSONObject() : JSONObject.parseObject(JSON.toJSONString(response.getData()));
        String state = "remove".equals(action) ? "deleted" : "active";
        jdbcTemplate.update("UPDATE docker_app_instance SET state=?,last_error=NULL,compose_path=?,backup_path=COALESCE(?,backup_path),updated_time=? WHERE id=?",
                state, data.getString("composePath"), data.getString("backupPath"), System.currentTimeMillis(), id);
        event(id, node.getId(), action, "success", actionLabel(action) + " 成功");
        return overview();
    }

    private void bindDomainRoute(Long appId, long nodeId, String appName, int hostPort, Map<String, Object> params) {
        String domain = Objects.toString(params.get("domain"), "").trim();
        if (domain.isEmpty()) throw new IllegalArgumentException("开启域名绑定时必须填写访问域名");
        Long dnsZoneId = longValue(params.get("dnsZoneId"));
        if (dnsZoneId == null) throw new IllegalArgumentException("开启 HTTPS 托管时必须选择 DNS 域名配置");
        DomainRouteCreateDto dto = new DomainRouteCreateDto();
        dto.setName(appName + " HTTPS");
        dto.setDomain(domain);
        dto.setPathPrefix(Objects.toString(params.getOrDefault("pathPrefix", "/")));
        dto.setBackendType("direct");
        dto.setBackendNodeId(nodeId);
        dto.setBackendHost("127.0.0.1");
        dto.setBackendPort(hostPort);
        dto.setBackendScheme("http");
        dto.setBackendPath(Objects.toString(params.getOrDefault("backendPath", "/")));
        dto.setEntryNodeId(longValue(params.getOrDefault("entryNodeId", nodeId)));
        dto.setListenPort(intValue(params.get("listenPort"), 443));
        dto.setIngressMode("managed_https");
        dto.setDnsZoneId(dnsZoneId);
        R created = servicePublishingService.createDomainRoute(dto);
        if (created.getCode() != 0) {
            jdbcTemplate.update("UPDATE docker_app_instance SET last_error=?,updated_time=? WHERE id=?",
                    "Docker 已部署，但自动绑定域名失败：" + StringUtils.abbreviate(created.getMsg(), 450),
                    System.currentTimeMillis(), appId);
            event(appId, nodeId, "bind_domain", "failed", created.getMsg());
            return;
        }
        Object data = created.getData();
        Long routeId = null;
        if (data instanceof Map<?, ?> map) routeId = longValue(map.get("id"));
        try {
            JSONObject json = JSONObject.parseObject(JSON.toJSONString(data));
            routeId = json.getLong("id");
        } catch (Exception ignored) {
        }
        jdbcTemplate.update("UPDATE docker_app_instance SET domain_route_id=?,updated_time=? WHERE id=?",
                routeId, System.currentTimeMillis(), appId);
        event(appId, nodeId, "bind_domain", "success", "已创建域名入口并开始申请 HTTPS 证书");
    }

    private boolean isActive(Long id) {
        String state = jdbcTemplate.queryForObject("SELECT state FROM docker_app_instance WHERE id=?", String.class, id);
        return "active".equals(state);
    }

    private Map<String, Object> buildPayload(Template template, String name, String containerName, int hostPort) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("templateId", template.id());
        payload.put("name", name);
        payload.put("containerName", containerName);
        payload.put("image", template.image());
        payload.put("hostPort", hostPort);
        payload.put("containerPort", template.containerPort());
        payload.put("volumeName", "flux_" + containerName.replaceAll("[^a-zA-Z0-9_.-]", "_") + "_data");
        payload.put("env", template.env());
        return payload;
    }

    private String manualCommand(Map<String, Object> payload, String action) {
        String container = Objects.toString(payload.get("containerName"));
        String image = Objects.toString(payload.get("image"));
        int hostPort = intValue(payload.get("hostPort"), 0);
        int containerPort = intValue(payload.get("containerPort"), 0);
        String volume = Objects.toString(payload.get("volumeName"));
        String base = "mkdir -p /etc/cloudnest/docker-apps && ";
        return switch (action) {
            case "upgrade" -> "docker pull " + image + " && docker rm -f " + container + " || true && "
                    + dockerRun(container, image, hostPort, containerPort, volume);
            case "backup" -> base + "docker run --rm -v " + volume + ":/data:ro -v /etc/cloudnest/docker-apps:/backup alpine "
                    + "tar czf /backup/" + container + "-$(date +%Y%m%d%H%M%S).tar.gz -C /data .";
            case "stop" -> "docker stop " + container;
            case "start" -> "docker start " + container;
            case "remove", "rollback" -> "docker rm -f " + container + " || true";
            default -> "docker pull " + image + " && " + dockerRun(container, image, hostPort, containerPort, volume);
        };
    }

    private String dockerRun(String container, String image, int hostPort, int containerPort, String volume) {
        return "docker run -d --restart unless-stopped --name " + container
                + " -p " + hostPort + ":" + containerPort + " -v " + volume + ":/data " + image;
    }

    private List<Map<String, Object>> templates() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Template template : List.of(
                new Template("x-ui", "X-UI 面板", "代理面板", "ghcr.io/mhsanaei/3x-ui:latest", "latest", 2053, 2053, Map.of()),
                new Template("nezha", "哪吒监控", "监控探针", "ghcr.io/nezhahq/nezha:latest", "latest", 8008, 8008, Map.of()),
                new Template("alist", "Alist", "网盘目录", "xhofe/alist:latest", "latest", 5244, 5244, Map.of("PUID", "0", "PGID", "0", "UMASK", "022")),
                new Template("nextcloud", "Nextcloud", "私有云盘", "nextcloud:apache", "apache", 8080, 80, Map.of()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", template.id());
            row.put("name", template.name());
            row.put("category", template.category());
            row.put("image", template.image());
            row.put("versionLabel", template.versionLabel());
            row.put("defaultHostPort", template.defaultHostPort());
            row.put("containerPort", template.containerPort());
            items.add(row);
        }
        return items;
    }

    private Template template(String id) {
        return switch (Objects.toString(id, "").toLowerCase(Locale.ROOT)) {
            case "x-ui" -> new Template("x-ui", "X-UI 面板", "代理面板", "ghcr.io/mhsanaei/3x-ui:latest", "latest", 2053, 2053, Map.of());
            case "nezha" -> new Template("nezha", "哪吒监控", "监控探针", "ghcr.io/nezhahq/nezha:latest", "latest", 8008, 8008, Map.of());
            case "alist" -> new Template("alist", "Alist", "网盘目录", "xhofe/alist:latest", "latest", 5244, 5244, Map.of("PUID", "0", "PGID", "0", "UMASK", "022"));
            case "nextcloud" -> new Template("nextcloud", "Nextcloud", "私有云盘", "nextcloud:apache", "apache", 8080, 80, Map.of());
            default -> throw new IllegalArgumentException("暂不支持该应用模板");
        };
    }

    private Node requireNode(long nodeId) {
        Node node = nodeMapper.selectById(nodeId);
        if (node == null) throw new IllegalArgumentException("节点不存在");
        return node;
    }

    private Map<String, Object> app(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM docker_app_instance WHERE id=? AND state<>'deleted'", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void ensurePortFree(long nodeId, int port, Long currentId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM docker_app_instance WHERE node_id=? AND host_port=? AND state<>'deleted' AND (? IS NULL OR id<>?)",
                Integer.class, nodeId, port, currentId, currentId);
        if (count != null && count > 0) throw new IllegalArgumentException("该节点端口已被 Docker 应用中心占用：" + port);
    }

    private int nextPort(long nodeId, int preferred) {
        for (int port = Math.max(1024, preferred); port <= 65535; port++) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM docker_app_instance WHERE node_id=? AND host_port=? AND state<>'deleted'",
                    Integer.class, nodeId, port);
            if (count == null || count == 0) return port;
        }
        throw new IllegalStateException("没有可用端口");
    }

    private void markError(Long id, Long nodeId, String action, String message) {
        jdbcTemplate.update("UPDATE docker_app_instance SET state='error',last_error=?,updated_time=? WHERE id=?",
                StringUtils.abbreviate(message, 500), System.currentTimeMillis(), id);
        event(id, nodeId, action, "failed", message);
    }

    private void event(Long id, Long nodeId, String type, String status, String detail) {
        jdbcTemplate.update("INSERT INTO docker_app_event (app_id,node_id,event_type,status,detail,created_time) VALUES (?,?,?,?,?,?)",
                id, nodeId, type, status, StringUtils.abbreviate(detail, 500), System.currentTimeMillis());
    }

    private String cleanName(String value) {
        String name = StringUtils.trimToEmpty(value);
        if (name.length() < 2 || name.length() > 100) throw new IllegalArgumentException("应用名称长度需要 2-100 个字符");
        return name;
    }

    private String cleanContainerName(String value) {
        String name = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
        if (!SAFE_NAME.matcher(name).matches()) throw new IllegalArgumentException("容器名只能包含字母、数字、点、横线和下划线");
        return name;
    }

    private String normalizeAction(String action) {
        String value = Objects.toString(action, "deploy").toLowerCase(Locale.ROOT);
        if (!List.of("upgrade", "backup", "stop", "start", "remove", "rollback").contains(value)) {
            throw new IllegalArgumentException("不支持的应用操作");
        }
        return value;
    }

    private String actionLabel(String action) {
        return switch (action) {
            case "upgrade" -> "升级";
            case "backup" -> "备份";
            case "stop" -> "停止";
            case "start" -> "启动";
            case "remove" -> "删除";
            case "rollback" -> "回退";
            default -> "部署";
        };
    }

    private String required(Map<String, Object> params, String key) {
        String value = Objects.toString(params == null ? null : params.get(key), "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少参数：" + key);
        return value;
    }

    private boolean truth(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return null; }
    }

    private int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return fallback; }
    }

    private record Template(String id, String name, String category, String image, String versionLabel,
                            int defaultHostPort, int containerPort, Map<String, String> env) { }
}
