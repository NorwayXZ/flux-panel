package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AuthorizedEntryTargetValidator;
import com.admin.common.utils.IpAddressMatcher;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.AuthorizedEntryForward;
import com.admin.entity.AuthorizedEntryGrant;
import com.admin.entity.AuthorizedEntryPort;
import com.admin.entity.AuthorizedEntryTemplate;
import com.admin.mapper.AuthorizedEntryForwardMapper;
import com.admin.mapper.AuthorizedEntryGrantMapper;
import com.admin.mapper.AuthorizedEntryPortMapper;
import com.admin.mapper.AuthorizedEntryTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant-facing entry grants. The source failover group remains an administrator-owned
 * routing template; every tenant port receives separate managed forwards on its members.
 */
@Slf4j
@Service
public class AuthorizedEntryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long GIB = 1024L * 1024L * 1024L;

    @Resource private AuthorizedEntryTemplateMapper templateMapper;
    @Resource private AuthorizedEntryGrantMapper grantMapper;
    @Resource private AuthorizedEntryPortMapper portMapper;
    @Resource(name = "authorizedEntryForwardMapper") private AuthorizedEntryForwardMapper forwardMapper;
    @Resource private ForwardService forwardService;
    @Resource private JdbcTemplate jdbcTemplate;

    private final Map<Long, Object> grantLocks = new ConcurrentHashMap<>();
    private final Object allocationLock = new Object();

    public R listTemplates() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.id,t.name,t.source_group_id AS sourceGroupId,t.start_port AS startPort,t.end_port AS endPort,"
                        + "t.protocol_mode AS protocolMode,t.blocked_target_cidrs AS blockedTargetCidrs,t.status,g.domain,COUNT(gr.id) AS grantCount "
                        + "FROM authorized_entry_template t JOIN cross_entry_failover_group g ON g.id=t.source_group_id "
                        + "LEFT JOIN authorized_entry_grant gr ON gr.template_id=t.id "
                        + "GROUP BY t.id,t.name,t.source_group_id,t.start_port,t.end_port,t.protocol_mode,t.blocked_target_cidrs,t.status,g.domain ORDER BY t.created_time DESC");
        return R.ok(rows);
    }

    @Transactional(rollbackFor = Exception.class)
    public R saveTemplate(Map<String, Object> input) {
        Long id = longValue(input.get("id"));
        String name = StringUtils.trimToNull(stringValue(input.get("name")));
        Long sourceGroupId = longValue(input.get("sourceGroupId"));
        int startPort = intValue(input.get("startPort"), 0);
        int endPort = intValue(input.get("endPort"), 0);
        String protocol = normalizeProtocol(stringValue(input.get("protocolMode")));
        String blockedTargetCidrs;
        try {
            blockedTargetCidrs = normalizeBlockedCidrs(stringValue(input.get("blockedTargetCidrs")));
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        if (name == null || sourceGroupId == null || startPort < 1 || endPort < startPort || endPort > 65535) {
            return R.err("请填写名称、入口容灾组和有效端口范围");
        }
        SourceTemplate source = loadSourceTemplate(sourceGroupId);
        if (source.members().stream().map(SourceMember::nodeId).distinct().count() < 2) return R.err("入口模板至少需要两个不同节点的可用入口成员");
        AuthorizedEntryTemplate template = id == null ? new AuthorizedEntryTemplate() : templateMapper.selectById(id);
        if (template == null) return R.err("入口模板不存在");
        if (id != null && hasActiveGrant(template.getId()) && !Objects.equals(template.getSourceGroupId(), sourceGroupId)) {
            return R.err("已有用户授权时不能更换入口容灾组");
        }
        long now = System.currentTimeMillis();
        template.setName(name);
        template.setSourceGroupId(sourceGroupId);
        template.setStartPort(startPort);
        template.setEndPort(endPort);
        template.setProtocolMode(protocol);
        template.setBlockedTargetCidrs(blockedTargetCidrs);
        template.setStatus(1);
        template.setUpdatedTime(now);
        if (id == null) {
            template.setCreatedTime(now);
            templateMapper.insert(template);
        } else {
            templateMapper.updateById(template);
        }
        return R.ok(Map.of("id", template.getId()));
    }

    public R listGrants(Integer requestedUserId) {
        Integer current = currentUserId();
        boolean admin = isAdmin();
        if (!admin && current == null) return R.err("登录状态无效");
        if (!admin && requestedUserId != null && !Objects.equals(requestedUserId, current)) return R.err("无权查看其他用户授权");
        Integer userId = admin ? requestedUserId : current;
        List<AuthorizedEntryGrant> grants = grantMapper.selectList(new QueryWrapper<AuthorizedEntryGrant>()
                .eq(userId != null, "user_id", userId).orderByDesc("created_time"));
        long now = System.currentTimeMillis();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuthorizedEntryGrant grant : grants) {
            reconcile(grant, now);
            result.add(grantView(grantMapper.selectById(grant.getId()), admin));
        }
        return R.ok(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public R saveGrant(Map<String, Object> input) {
        Long id = longValue(input.get("id"));
        Long templateId = longValue(input.get("templateId"));
        Integer userId = intObject(input.get("userId"));
        String name = StringUtils.trimToNull(stringValue(input.get("name")));
        int maxPorts = intValue(input.get("maxPorts"), 0);
        String direction = normalizeDirection(stringValue(input.get("flowDirection")));
        int resetDay = intValue(input.get("flowResetDay"), 0);
        Long expiresAt = longValue(input.get("expiresAt"));
        Long limitGiB = longValue(input.get("flowLimitGiB"));
        if (limitGiB == null) limitGiB = 0L;
        if (templateId == null || userId == null || name == null || maxPorts < 1 || maxPorts > 100 || limitGiB < 0
                || limitGiB > Long.MAX_VALUE / GIB || resetDay < 0 || resetDay > 28 || (expiresAt != null && expiresAt <= 0)) {
            return R.err("授权参数不完整或超出允许范围");
        }
        long limit = limitGiB * GIB;
        AuthorizedEntryTemplate template = templateMapper.selectById(templateId);
        if (template == null || !Objects.equals(template.getStatus(), 1)) return R.err("入口模板不存在或已停用");
        Integer recipientCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user WHERE id=? AND role_id<>0", Integer.class, userId);
        if (recipientCount == null || recipientCount == 0) return R.err("授权用户不存在或不是普通用户");
        SourceTemplate source = loadSourceTemplate(template.getSourceGroupId());
        AuthorizedEntryGrant grant = id == null ? new AuthorizedEntryGrant() : grantMapper.selectById(id);
        if (grant == null) return R.err("入口授权不存在");
        if (id != null && (!Objects.equals(grant.getTemplateId(), templateId) || !Objects.equals(grant.getUserId(), userId))
                && portMapper.selectCount(new QueryWrapper<AuthorizedEntryPort>().eq("grant_id", id)) > 0) {
            return R.err("已有端口时不能更换用户或入口模板");
        }
        if (id != null && portMapper.selectCount(new QueryWrapper<AuthorizedEntryPort>().eq("grant_id", id).ne("state", "deleted")) > maxPorts) {
            return R.err("端口数量不能低于已经分配的授权端口数");
        }
        long now = System.currentTimeMillis();
        grant.setTemplateId(templateId);
        grant.setUserId(userId);
        grant.setName(name);
        grant.setAccessHost(source.domain());
        grant.setMaxPorts(maxPorts);
        grant.setFlowLimitBytes(limit);
        grant.setFlowDirection(direction);
        grant.setFlowResetDay(resetDay);
        grant.setExpiresAt(expiresAt);
        if (id == null) {
            grant.setState("active");
            grant.setLastError(null);
        }
        grant.setUpdatedTime(now);
        if (id == null) {
            grant.setUsedBytes(0L);
            grant.setLastResetAt(now);
            grant.setCreatedTime(now);
            grantMapper.insert(grant);
        } else {
            grantMapper.updateById(grant);
            reconcile(grantMapper.selectById(grant.getId()), now);
        }
        return R.ok(Map.of("id", grant.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public R createPort(Map<String, Object> input) {
        Long grantId = longValue(input.get("grantId"));
        Integer userId = currentUserId();
        if (grantId == null || userId == null) return R.err("缺少入口授权");
        AuthorizedEntryGrant grant = grantMapper.selectById(grantId);
        if (grant == null || !Objects.equals(grant.getUserId(), userId)) return R.err("入口授权不存在");
        Object lock = grantLocks.computeIfAbsent(grantId, ignored -> new Object());
        synchronized (lock) {
            reconcile(grant, System.currentTimeMillis());
            grant = grantMapper.selectById(grantId);
            if (!"active".equals(grant.getState())) return R.err("该授权当前不可用：" + publicStateMessage(grant.getState()));
            Integer targetPort = intObject(input.get("targetPort"));
            String target;
            try {
                target = AuthorizedEntryTargetValidator.validatePublicLiteral(stringValue(input.get("targetHost")), targetPort);
            } catch (IllegalArgumentException e) {
                return R.err(e.getMessage());
            }
            AuthorizedEntryTemplate template = templateMapper.selectById(grant.getTemplateId());
            if (template == null || !Objects.equals(template.getStatus(), 1)) return R.err("入口模板已停用");
            if (isForbiddenTarget(target, template)) return R.err("落地不能使用平台节点、管理地址或模板禁止网段");
            if (portMapper.selectCount(new QueryWrapper<AuthorizedEntryPort>().eq("grant_id", grantId).ne("state", "deleted")) >= grant.getMaxPorts()) {
                return R.err("已达到该授权的端口数量上限");
            }
            SourceTemplate source = loadSourceTemplate(template.getSourceGroupId());
            int port;
            synchronized (allocationLock) {
                port = allocatePort(template, source, null);
            }
            AuthorizedEntryPort entryPort = new AuthorizedEntryPort();
            long now = System.currentTimeMillis();
            entryPort.setGrantId(grantId);
            entryPort.setUserId(userId);
            entryPort.setPort(port);
            entryPort.setTargetHost(stripPort(target));
            entryPort.setTargetPort(targetPort);
            entryPort.setProtocolMode(template.getProtocolMode());
            entryPort.setState("provisioning");
            entryPort.setInFlow(0L);
            entryPort.setOutFlow(0L);
            entryPort.setChargedBytes(0L);
            entryPort.setCreatedTime(now);
            entryPort.setUpdatedTime(now);
            portMapper.insert(entryPort);
            R provision = provision(entryPort, source, target);
            if (provision.getCode() != 0) {
                Integer bindings = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authorized_entry_forward WHERE port_id=?", Integer.class, entryPort.getId());
                if (bindings == null || bindings == 0) {
                    portMapper.deleteById(entryPort.getId());
                } else {
                    portMapper.update(null, new UpdateWrapper<AuthorizedEntryPort>().eq("id", entryPort.getId())
                            .set("state", "error").set("last_error", provision.getMsg()).set("updated_time", System.currentTimeMillis()));
                }
                return provision;
            }
            entryPort.setState("active");
            entryPort.setUpdatedTime(System.currentTimeMillis());
            portMapper.updateById(entryPort);
            return R.ok(portView(entryPort));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public R deletePort(Long portId) {
        AuthorizedEntryPort port = portMapper.selectById(portId);
        if (port == null || (!isAdmin() && !Objects.equals(port.getUserId(), currentUserId()))) return R.err("授权端口不存在");
        R cleanup = cleanupPort(port);
        if (cleanup.getCode() != 0) {
            portMapper.update(null, new UpdateWrapper<AuthorizedEntryPort>().eq("id", port.getId())
                    .set("state", "error").set("last_error", cleanup.getMsg()).set("updated_time", System.currentTimeMillis()));
            return cleanup;
        }
        port.setState("deleted");
        port.setUpdatedTime(System.currentTimeMillis());
        portMapper.updateById(port);
        return R.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public R updatePort(Long portId, Map<String, Object> input) {
        AuthorizedEntryPort port = portMapper.selectById(portId);
        if (port == null || (!isAdmin() && !Objects.equals(port.getUserId(), currentUserId()))) return R.err("授权端口不存在");
        AuthorizedEntryGrant grant = grantMapper.selectById(port.getGrantId());
        if (grant == null || !"active".equals(grant.getState())) return R.err("所属入口授权当前不可用");
        Integer targetPort = intObject(input.get("targetPort"));
        String target;
        try {
            target = AuthorizedEntryTargetValidator.validatePublicLiteral(stringValue(input.get("targetHost")), targetPort);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        AuthorizedEntryTemplate template = templateMapper.selectById(grant.getTemplateId());
        if (template == null) return R.err("入口模板不存在");
        if (isForbiddenTarget(target, template)) return R.err("落地不能使用平台节点、管理地址或模板禁止网段");
        String previous = toTarget(port.getTargetHost(), port.getTargetPort());
        List<AuthorizedEntryForward> bindings = forwardMapper.selectList(new QueryWrapper<AuthorizedEntryForward>().eq("port_id", port.getId()));
        if (bindings.isEmpty()) return R.err("授权端口缺少托管转发，请联系管理员修复");
        List<AuthorizedEntryForward> updated = new ArrayList<>();
        for (AuthorizedEntryForward binding : bindings) {
            R result = updateManagedTarget(binding.getForwardId(), target);
            if (result.getCode() != 0) {
                boolean restored = true;
                for (AuthorizedEntryForward changed : updated) {
                    if (updateManagedTarget(changed.getForwardId(), previous).getCode() != 0) restored = false;
                }
                portMapper.update(null, new UpdateWrapper<AuthorizedEntryPort>().eq("id", port.getId())
                        .set("state", restored ? "active" : "error").set("last_error", result.getMsg()).set("updated_time", System.currentTimeMillis()));
                return R.err("更新落地失败，已" + (restored ? "恢复原落地" : "进入故障状态") + "：" + result.getMsg());
            }
            updated.add(binding);
        }
        portMapper.update(null, new UpdateWrapper<AuthorizedEntryPort>().eq("id", port.getId())
                .set("target_host", stripPort(target)).set("target_port", targetPort).set("state", "active")
                .set("last_error", null).set("updated_time", System.currentTimeMillis()));
        return R.ok();
    }

    public R setGrantState(Long grantId, boolean active) {
        AuthorizedEntryGrant grant = grantMapper.selectById(grantId);
        if (grant == null) return R.err("入口授权不存在");
        Object lock = grantLocks.computeIfAbsent(grantId, ignored -> new Object());
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (!active) {
                return pauseGrant(grant, "admin_paused", "管理员暂停入口授权");
            }
            if (grant.getExpiresAt() != null && grant.getExpiresAt() <= now) return R.err("入口授权已到期，请续期后再恢复");
            if (grant.getFlowLimitBytes() > 0 && grant.getUsedBytes() >= grant.getFlowLimitBytes()) return R.err("入口授权流量额度已用尽，请重置或提高额度");
            return resumeGrant(grant);
        }
    }

    public R revokeGrant(Long grantId) {
        AuthorizedEntryGrant grant = grantMapper.selectById(grantId);
        if (grant == null || "deleted".equals(grant.getState())) return R.err("入口授权不存在");
        synchronized (grantLocks.computeIfAbsent(grantId, ignored -> new Object())) {
            List<String> failures = new ArrayList<>();
            for (AuthorizedEntryPort port : portMapper.selectList(new QueryWrapper<AuthorizedEntryPort>().eq("grant_id", grantId).ne("state", "deleted"))) {
                R cleanup = cleanupPort(port);
                if (cleanup.getCode() == 0) {
                    portMapper.update(null, new UpdateWrapper<AuthorizedEntryPort>().eq("id", port.getId())
                            .set("state", "deleted").set("updated_time", System.currentTimeMillis()));
                } else {
                    failures.add("端口 " + port.getPort() + "：" + cleanup.getMsg());
                    portMapper.update(null, new UpdateWrapper<AuthorizedEntryPort>().eq("id", port.getId())
                            .set("state", "error").set("last_error", cleanup.getMsg()).set("updated_time", System.currentTimeMillis()));
                }
            }
            if (!failures.isEmpty()) {
                pauseGrant(grant, "admin_paused", String.join("；", failures));
                return R.err("撤销未完成，请修复后重试：" + String.join("；", failures));
            }
            grantMapper.update(null, new UpdateWrapper<AuthorizedEntryGrant>().eq("id", grantId)
                    .set("state", "deleted").set("last_error", null).set("updated_time", System.currentTimeMillis()));
            return R.ok();
        }
    }

    public R revokeForUser(Integer userId) {
        for (AuthorizedEntryGrant grant : grantMapper.selectList(new QueryWrapper<AuthorizedEntryGrant>()
                .eq("user_id", userId).ne("state", "deleted"))) {
            R result = revokeGrant(grant.getId());
            if (result.getCode() != 0) return result;
        }
        return R.ok();
    }

    public boolean isManagedForward(Long forwardId) {
        if (forwardId == null) return false;
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authorized_entry_forward WHERE forward_id=?", Integer.class, forwardId);
        return count != null && count > 0;
    }

    public void recordTraffic(Long forwardId, Long reportingNodeId, long inbound, long outbound) {
        if (forwardId == null || reportingNodeId == null || (inbound <= 0 && outbound <= 0)) return;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id AS portId,p.grant_id AS grantId FROM authorized_entry_forward f "
                        + "JOIN authorized_entry_port p ON p.id=f.port_id WHERE f.forward_id=? AND f.node_id=? AND p.state='active'",
                forwardId, reportingNodeId);
        for (Map<String, Object> row : rows) {
            Long grantId = longValue(row.get("grantId"));
            if (grantId == null) continue;
            synchronized (grantLocks.computeIfAbsent(grantId, ignored -> new Object())) {
                AuthorizedEntryGrant grant = grantMapper.selectById(grantId);
                if (grant == null) continue;
                reconcile(grant, System.currentTimeMillis());
                if (!"active".equals(grant.getState())) continue;
                long charge = charge(grant.getFlowDirection(), inbound, outbound);
                long now = System.currentTimeMillis();
                jdbcTemplate.update("UPDATE authorized_entry_port SET in_flow=in_flow+?,out_flow=out_flow+?,charged_bytes=charged_bytes+?,updated_time=? WHERE id=?",
                        Math.max(0L, inbound), Math.max(0L, outbound), charge, now, row.get("portId"));
                jdbcTemplate.update("UPDATE authorized_entry_grant SET used_bytes=used_bytes+?,updated_time=? WHERE id=?",
                        charge, now, grantId);
                grant = grantMapper.selectById(grantId);
                if (grant.getFlowLimitBytes() > 0 && grant.getUsedBytes() >= grant.getFlowLimitBytes()) {
                    pauseGrant(grant, "quota_exhausted", "入口套餐流量额度已用尽");
                }
            }
        }
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void reconcileGrants() {
        long now = System.currentTimeMillis();
        for (AuthorizedEntryGrant grant : grantMapper.selectList(new QueryWrapper<AuthorizedEntryGrant>().ne("state", "deleted"))) {
            synchronized (grantLocks.computeIfAbsent(grant.getId(), ignored -> new Object())) {
                reconcile(grant, now);
            }
        }
    }

    private void reconcile(AuthorizedEntryGrant grant, long now) {
        if (grant == null || "deleted".equals(grant.getState())) return;
        if (List.of("quota_exhausted", "expired", "admin_paused").contains(grant.getState())) {
            pauseGrant(grant, grant.getState(), grant.getLastError());
            return;
        }
        if (grant.getExpiresAt() != null && grant.getExpiresAt() <= now) {
            pauseGrant(grant, "expired", "入口套餐授权已到期");
            return;
        }
        long cycle = cycleStart(grant.getFlowResetDay(), now);
        if (grant.getFlowResetDay() != null && grant.getFlowResetDay() > 0
                && (grant.getLastResetAt() == null || grant.getLastResetAt() < cycle)) {
            grantMapper.update(null, new UpdateWrapper<AuthorizedEntryGrant>().eq("id", grant.getId())
                    .set("used_bytes", 0L).set("last_reset_at", cycle).set("updated_time", now));
            if ("quota_exhausted".equals(grant.getState())) resumeGrant(grant);
        }
    }

    private R provision(AuthorizedEntryPort port, SourceTemplate source, String target) {
        try {
            for (SourceMember member : source.members()) {
                ForwardDto dto = new ForwardDto();
                dto.setName("授权入口 " + port.getId() + " · " + member.nodeId());
                dto.setTunnelId(member.tunnelId().intValue());
                dto.setInPort(port.getPort());
                dto.setRemoteAddr(target);
                dto.setProtocolMode(port.getProtocolMode());
                dto.setRouteBalanceStrategy("round");
                R result = forwardService.createManagedForward(dto, source.ownerUserId());
                if (result.getCode() != 0) throw new IllegalStateException(result.getMsg());
                Object data = result.getData();
                Long forwardId = data instanceof Map<?, ?> map ? longValue(map.get("id")) : null;
                if (forwardId == null) throw new IllegalStateException("托管转发未返回 ID");
                AuthorizedEntryForward binding = new AuthorizedEntryForward();
                binding.setPortId(port.getId());
                binding.setForwardId(forwardId);
                binding.setNodeId(member.nodeId());
                binding.setCreatedTime(System.currentTimeMillis());
                binding.setUpdatedTime(System.currentTimeMillis());
                forwardMapper.insert(binding);
            }
            return R.ok();
        } catch (Exception e) {
            R cleanup = cleanupPort(port);
            String failure = "创建授权入口失败：" + StringUtils.defaultIfBlank(e.getMessage(), "请检查入口节点和端口资源");
            if (cleanup.getCode() != 0) failure += "；" + cleanup.getMsg();
            return R.err(failure);
        }
    }

    private R cleanupPort(AuthorizedEntryPort port) {
        for (AuthorizedEntryForward binding : forwardMapper.selectList(new QueryWrapper<AuthorizedEntryForward>().eq("port_id", port.getId()))) {
            if (forwardService.getById(binding.getForwardId()) == null) {
                forwardMapper.deleteById(binding.getId());
                continue;
            }
            R result = forwardService.deleteManagedForward(binding.getForwardId());
            if (result.getCode() != 0) return R.err("删除隐藏入口失败：" + result.getMsg());
            forwardMapper.deleteById(binding.getId());
        }
        return R.ok();
    }

    private R pauseGrant(AuthorizedEntryGrant grant, String state, String reason) {
        List<String> failures = new ArrayList<>();
        for (AuthorizedEntryPort port : portMapper.selectList(new QueryWrapper<AuthorizedEntryPort>().eq("grant_id", grant.getId()).ne("state", "deleted"))) {
            boolean paused = true;
            for (AuthorizedEntryForward binding : forwardMapper.selectList(new QueryWrapper<AuthorizedEntryForward>().eq("port_id", port.getId()))) {
                R result = forwardService.pauseManagedForward(binding.getForwardId());
                if (result.getCode() != 0) {
                    paused = false;
                    failures.add("端口 " + port.getPort() + "：" + result.getMsg());
                }
            }
            portMapper.update(null, new UpdateWrapper<AuthorizedEntryPort>().eq("id", port.getId())
                    .set("state", paused ? state : "error").set("last_error", paused ? reason : String.join("；", failures))
                    .set("updated_time", System.currentTimeMillis()));
        }
        String detail = failures.isEmpty() ? reason : StringUtils.abbreviate(String.join("；", failures), 500);
        grantMapper.update(null, new UpdateWrapper<AuthorizedEntryGrant>().eq("id", grant.getId())
                .set("state", state).set("last_error", detail).set("updated_time", System.currentTimeMillis()));
        return failures.isEmpty() ? R.ok() : R.err("部分隐藏入口暂停失败，系统将自动重试：" + detail);
    }

    private R resumeGrant(AuthorizedEntryGrant grant) {
        long now = System.currentTimeMillis();
        List<String> failures = new ArrayList<>();
        for (AuthorizedEntryPort port : portMapper.selectList(new QueryWrapper<AuthorizedEntryPort>().eq("grant_id", grant.getId())
                .in("state", "quota_exhausted", "admin_paused", "expired", "error"))) {
            boolean resumed = true;
            for (AuthorizedEntryForward binding : forwardMapper.selectList(new QueryWrapper<AuthorizedEntryForward>().eq("port_id", port.getId()))) {
                R result = forwardService.resumeManagedForward(binding.getForwardId());
                if (result.getCode() != 0) {
                    resumed = false;
                    failures.add("端口 " + port.getPort() + "：" + result.getMsg());
                }
            }
            portMapper.update(null, new UpdateWrapper<AuthorizedEntryPort>().eq("id", port.getId())
                    .set("state", resumed ? "active" : "error").set("last_error", resumed ? null : String.join("；", failures)).set("updated_time", now));
        }
        if (!failures.isEmpty()) {
            String detail = StringUtils.abbreviate(String.join("；", failures), 500);
            grantMapper.update(null, new UpdateWrapper<AuthorizedEntryGrant>().eq("id", grant.getId())
                    .set("state", "error").set("last_error", detail).set("updated_time", now));
            return R.err("部分隐藏入口恢复失败：" + detail);
        }
        grantMapper.update(null, new UpdateWrapper<AuthorizedEntryGrant>().eq("id", grant.getId())
                .set("state", "active").set("last_error", null).set("updated_time", now));
        return R.ok();
    }

    private int allocatePort(AuthorizedEntryTemplate template, SourceTemplate source, Integer requested) {
        int start = template.getStartPort();
        int end = template.getEndPort();
        if (requested != null && (requested < start || requested > end)) throw new IllegalArgumentException("端口不在授权模板范围内");
        List<Integer> candidates = requested == null ? java.util.stream.IntStream.rangeClosed(start, end).boxed().toList() : List.of(requested);
        for (Integer port : candidates) {
            if (portMapper.selectCount(new QueryWrapper<AuthorizedEntryPort>().eq("port", port).ne("state", "deleted")) > 0) continue;
            boolean occupied = false;
            for (SourceMember member : source.members()) {
                Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM forward f JOIN tunnel t ON t.id=f.tunnel_id "
                        + "WHERE t.in_node_id=? AND f.in_port=? AND f.status<>-1", Integer.class, member.nodeId(), port);
                if (count != null && count > 0) { occupied = true; break; }
            }
            if (!occupied) return port;
        }
        throw new IllegalStateException("入口模板当前没有可用端口");
    }

    private SourceTemplate loadSourceTemplate(Long groupId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT g.user_id AS ownerUserId,g.domain,m.entry_node_id AS nodeId,f.tunnel_id AS tunnelId "
                + "FROM cross_entry_failover_group g JOIN cross_entry_failover_member m ON m.group_id=g.id "
                + "JOIN forward f ON f.id=m.forward_id JOIN user u ON u.id=g.user_id AND u.role_id=0 "
                + "WHERE g.id=? AND g.enabled=1 AND m.enabled=1 ORDER BY m.priority", groupId);
        if (rows.isEmpty()) throw new IllegalArgumentException("入口容灾组不存在、已停用、没有可用成员，或并非管理员拥有");
        String domain = StringUtils.trimToNull(stringValue(rows.get(0).get("domain")));
        if (domain == null) throw new IllegalArgumentException("入口容灾组没有可供用户访问的域名");
        List<SourceMember> members = rows.stream().map(row -> new SourceMember(longValue(row.get("nodeId")), longValue(row.get("tunnelId")))).toList();
        return new SourceTemplate(intObject(rows.get(0).get("ownerUserId")), domain, members);
    }

    private boolean isForbiddenTarget(String target, AuthorizedEntryTemplate template) {
        if (isNodeAddress(target)) return true;
        String host = stripPort(target).replace("[", "").replace("]", "");
        return StringUtils.isNotBlank(template.getBlockedTargetCidrs()) && IpAddressMatcher.isAllowed(host, template.getBlockedTargetCidrs());
    }

    private boolean isNodeAddress(String target) {
        String host = stripPort(target).replace("[", "").replace("]", "");
        for (Map<String, Object> node : jdbcTemplate.queryForList("SELECT server_ip AS serverIp,ip FROM node")) {
            if (host.equalsIgnoreCase(stringValue(node.get("serverIp"))) || host.equalsIgnoreCase(stringValue(node.get("ip")))) return true;
        }
        return false;
    }

    private boolean hasActiveGrant(Long templateId) {
        return grantMapper.selectCount(new QueryWrapper<AuthorizedEntryGrant>().eq("template_id", templateId).ne("state", "deleted")) > 0;
    }

    private R updateManagedTarget(Long forwardId, String target) {
        com.admin.entity.Forward forward = forwardService.getById(forwardId);
        if (forward == null) return R.err("托管转发不存在");
        ForwardUpdateDto update = new ForwardUpdateDto();
        update.setId(forward.getId());
        update.setUserId(forward.getUserId());
        update.setName(forward.getName());
        update.setTunnelId(forward.getTunnelId());
        update.setRemoteAddr(target);
        update.setInPort(forward.getInPort());
        update.setInterfaceName(forward.getInterfaceName());
        update.setStrategy(forward.getStrategy());
        update.setRouteMode(forward.getRouteMode());
        update.setRouteBalanceStrategy(forward.getRouteBalanceStrategy());
        update.setProtocolMode(forward.getProtocolMode());
        return forwardService.updateManagedForward(update);
    }

    private String normalizeBlockedCidrs(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        List<String> result = new ArrayList<>();
        for (String item : raw.split("[,\\s]+")) {
            if (StringUtils.isBlank(item)) continue;
            String[] parts = item.trim().split("/", 2);
            if (parts.length != 2 || !parts[0].matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException("禁止网段必须使用 IP/CIDR 格式");
            try {
                InetAddress address = InetAddress.getByName(parts[0]);
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > address.getAddress().length * 8) throw new IllegalArgumentException();
            } catch (Exception e) {
                throw new IllegalArgumentException("禁止网段必须使用有效的 IP/CIDR 格式");
            }
            if (!result.contains(item.trim())) result.add(item.trim());
            if (result.size() > 64) throw new IllegalArgumentException("额外禁止网段最多 64 条");
        }
        return result.isEmpty() ? null : String.join("\n", result);
    }

    private Map<String, Object> grantView(AuthorizedEntryGrant grant, boolean admin) {
        AuthorizedEntryTemplate template = templateMapper.selectById(grant.getTemplateId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", grant.getId()); view.put("name", grant.getName()); view.put("accessHost", grant.getAccessHost());
        view.put("maxPorts", grant.getMaxPorts()); view.put("flowLimitBytes", grant.getFlowLimitBytes());
        view.put("usedBytes", grant.getUsedBytes()); view.put("flowDirection", grant.getFlowDirection());
        view.put("flowResetDay", grant.getFlowResetDay()); view.put("expiresAt", grant.getExpiresAt()); view.put("state", grant.getState());
        view.put("ports", portMapper.selectList(new QueryWrapper<AuthorizedEntryPort>().eq("grant_id", grant.getId()).ne("state", "deleted"))
                .stream().sorted(Comparator.comparing(AuthorizedEntryPort::getPort)).map(this::portView).toList());
        if (admin) { view.put("userId", grant.getUserId()); view.put("templateId", grant.getTemplateId()); view.put("templateName", template == null ? "已删除模板" : template.getName()); view.put("lastError", grant.getLastError()); }
        return view;
    }

    private Map<String, Object> portView(AuthorizedEntryPort port) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", port.getId()); view.put("port", port.getPort()); view.put("targetHost", port.getTargetHost());
        view.put("targetPort", port.getTargetPort()); view.put("protocolMode", port.getProtocolMode()); view.put("state", port.getState());
        view.put("inFlow", port.getInFlow()); view.put("outFlow", port.getOutFlow()); view.put("chargedBytes", port.getChargedBytes());
        return view;
    }

    private long charge(String direction, long in, long out) {
        long inbound = Math.max(0L, in), outbound = Math.max(0L, out);
        if ("inbound".equals(direction)) return inbound;
        if ("outbound".equals(direction)) return outbound;
        return inbound > Long.MAX_VALUE - outbound ? Long.MAX_VALUE : inbound + outbound;
    }

    private long cycleStart(Integer resetDay, long now) {
        if (resetDay == null || resetDay <= 0) return now;
        LocalDate date = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();
        int day = Math.min(resetDay, date.lengthOfMonth());
        if (date.getDayOfMonth() < day) { date = date.minusMonths(1); day = Math.min(resetDay, date.lengthOfMonth()); }
        return date.withDayOfMonth(day).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    private String normalizeProtocol(String value) { return "tcp_udp".equals(value) ? "tcp_udp" : "tcp"; }
    private String normalizeDirection(String value) { return List.of("inbound", "outbound", "total").contains(value) ? value : "total"; }
    private Integer currentUserId() { return JwtUtil.getUserIdFromToken(); }
    private boolean isAdmin() { return Objects.equals(JwtUtil.getRoleIdFromToken(), 0); }
    private Long longValue(Object value) { if (value instanceof Number n) return n.longValue(); try { return value == null ? null : Long.parseLong(value.toString()); } catch (Exception e) { return null; } }
    private Integer intObject(Object value) { Long number = longValue(value); return number == null ? null : number.intValue(); }
    private int intValue(Object value, int fallback) { Integer result = intObject(value); return result == null ? fallback : result; }
    private String stringValue(Object value) { return value == null ? null : value.toString(); }
    private String stripPort(String target) { int i = target.lastIndexOf(':'); return target.substring(0, i); }
    private String toTarget(String host, Integer port) { return host != null && host.contains(":") && !host.startsWith("[") ? "[" + host + "]:" + port : host + ":" + port; }
    private String publicStateMessage(String state) { return switch (state) { case "quota_exhausted" -> "流量额度已用尽"; case "expired" -> "已到期"; case "admin_paused" -> "已被管理员暂停"; default -> "暂不可用"; }; }
    private record SourceMember(Long nodeId, Long tunnelId) {}
    private record SourceTemplate(Integer ownerUserId, String domain, List<SourceMember> members) {}
}
