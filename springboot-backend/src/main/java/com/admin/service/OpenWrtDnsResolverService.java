package com.admin.service;

import com.admin.common.dto.OpenWrtDnsResolverConfigDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.InternalConnector;
import com.admin.mapper.InternalConnectorMapper;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.ConcurrentHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OpenWrtDnsResolverService {
    private static final Set<String> CARRIERS = Set.of("telecom", "unicom", "mobile");
    private static final Pattern INTERFACE = Pattern.compile("[A-Za-z0-9_.:-]{1,15}");
    private final JdbcTemplate jdbc;
    private final InternalConnectorMapper connectors;
    private final Map<Long,String> syncedPolicies = new ConcurrentHashMap<>();

    public OpenWrtDnsResolverService(JdbcTemplate jdbc, InternalConnectorMapper connectors) {
        this.jdbc = jdbc;
        this.connectors = connectors;
    }

    public R options() {
        List<Map<String,Object>> groups = jdbc.queryForList("SELECT g.id,g.name,g.domain,g.record_type AS recordType,g.state,g.enabled,"
                + "COUNT(r.id) AS routeCount FROM smart_entry_group g JOIN smart_entry_route r ON r.group_id=g.id "
                + "WHERE g.enabled=1 GROUP BY g.id,g.name,g.domain,g.record_type,g.state,g.enabled ORDER BY g.name");
        return R.ok(Map.of("groups", groups,"carrierDatabase",jdbc.queryForList("SELECT carrier,state,cidr_count AS cidrCount,updated_time AS updatedAt FROM source_ip_carrier_database ORDER BY carrier")));
    }

    public R list() {
        int userId = Objects.equals(JwtUtil.getRoleIdFromToken(),0) ? 0 : JwtUtil.getUserIdFromToken();
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT c.id AS connectorId,c.name,c.platform,c.connector_role AS connectorRole,"
                + "c.version,c.remote_ip AS remoteIp,c.last_seen AS lastSeen,c.status AS connectorStatus,"
                + "r.interface_carriers AS interfaceCarriers,r.smart_entry_group_ids AS smartEntryGroupIds,"
                + "r.active_interface AS activeInterface,r.active_carrier AS activeCarrier,r.policy_revision AS policyRevision,r.applied_revision AS appliedRevision,r.status_json AS statusJson,"
                + "r.resolved_queries AS resolvedQueries,r.dnsmasq_reloaded AS dnsmasqReloaded,COALESCE(NULLIF(r.last_error,''),r.sync_error) AS lastError,r.reported_at AS reportedAt "
                + "FROM internal_connector c LEFT JOIN openwrt_dns_resolver r ON r.connector_id=c.id "
                + "WHERE c.connector_role='openwrt_dns' AND c.status=1 AND (?=0 OR c.user_id=?) ORDER BY c.created_time DESC", userId, userId);
        for (Map<String,Object> row : rows) {
            long id = ((Number)row.get("connectorId")).longValue();
            row.put("online", WebSocketServer.isConnectorOnline(id));
            row.put("interfaceCarriers", parseObject(Objects.toString(row.get("interfaceCarriers"), "{}")));
            row.put("smartEntryGroupIds", parseLongs(Objects.toString(row.get("smartEntryGroupIds"), "[]")));
            var status=JSON.parseObject(Objects.toString(row.remove("statusJson"),"{}"));
            for(String key:List.of("activeInterface6","activeCarrier6","publicIp","publicIp6","detectionSource","detectionSource6","detectionError"))row.put(key,status==null?null:status.get(key));
        }
        return R.ok(rows);
    }

    @Transactional(rollbackFor = Exception.class)
    public R configure(OpenWrtDnsResolverConfigDto dto) {
        InternalConnector connector = ownedResolver(dto.getConnectorId());
        if (connector == null) return R.err("OpenWrt DNS Agent 不存在或无权访问");
        Map<String,String> interfaces = normalizeInterfaces(dto.getInterfaceCarriers());
        List<Long> groupIds = normalizeGroups(dto.getSmartEntryGroupIds());
        String groupsJson = JSON.toJSONString(groupIds);
        int userId = JwtUtil.getUserIdFromToken().intValue();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO openwrt_dns_resolver (connector_id,user_id,interface_carriers,smart_entry_group_ids,"
                        + "policy_revision,active_interface,active_carrier,resolved_queries,dnsmasq_reloaded,last_error,reported_at,created_time,updated_time) "
                        + "VALUES (?,?,?,?,1,NULL,'default',0,0,NULL,NULL,?,?) ON DUPLICATE KEY UPDATE "
                        + "interface_carriers=VALUES(interface_carriers),smart_entry_group_ids=VALUES(smart_entry_group_ids),"
                        + "policy_revision=policy_revision+1,policy_hash=NULL,last_error=NULL,sync_error=NULL,updated_time=VALUES(updated_time)",
                connector.getId(),userId,JSON.toJSONString(interfaces),groupsJson,now,now);
        syncedPolicies.remove(connector.getId());
        return R.ok(Map.of("state","pending","message","配置已保存，后台将同步至 OpenWrt，离线时保留待同步"));
    }

    public void connectorOnline(long connectorId) {
        InternalConnector connector = connectors.selectById(connectorId);
        if (connector == null || !"openwrt_dns".equals(connector.getConnectorRole())) return;
        syncedPolicies.remove(connectorId);
    }

    public void handleStatus(long connectorId, Map<String,Object> status) {
        if (status == null) return;
        jdbc.update("UPDATE openwrt_dns_resolver SET active_interface=?,active_carrier=?,resolved_queries=?,dnsmasq_reloaded=?,"
                        + "last_error=?,reported_at=?,updated_time=?,applied_revision=?,status_json=? WHERE connector_id=?",
                status.get("activeInterface"),status.get("activeCarrier"),status.get("resolvedQueries"),
                Boolean.TRUE.equals(status.get("dnsmasqReloaded"))?1:0,StringUtils.abbreviate(Objects.toString(status.get("lastError"),null),500),System.currentTimeMillis(),
                System.currentTimeMillis(),status.get("revision"),JSON.toJSONString(status),connectorId);
        jdbc.update("UPDATE internal_connector SET last_seen=?,updated_time=? WHERE id=? AND connector_role='openwrt_dns'",System.currentTimeMillis(),System.currentTimeMillis(),connectorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public R removePolicy(long connectorId) {
        InternalConnector connector=ownedResolver(connectorId);
        if(connector==null)return R.err("OpenWrt DNS Agent 不存在或无权访问");
        jdbc.update("UPDATE openwrt_dns_resolver SET smart_entry_group_ids='[]',policy_revision=policy_revision+1,policy_hash=NULL,updated_time=? WHERE connector_id=?",System.currentTimeMillis(),connectorId);
        syncedPolicies.remove(connectorId);
        return R.ok("移除任务已保存，Agent 上线后会清理本地 DNS 规则");
    }

    @Scheduled(initialDelay=30000,fixedDelay=10000)
    public void syncPolicies() {
        List<Long> ids=jdbc.queryForList("SELECT r.connector_id FROM openwrt_dns_resolver r JOIN internal_connector c ON c.id=r.connector_id "
                + "WHERE c.status=1 AND c.connector_role='openwrt_dns'",Long.class);
        for(Long id:ids) {
            if(!WebSocketServer.isConnectorOnline(id)){syncedPolicies.remove(id);continue;}
            try {
                Map<String,Object> policy=policy(id);if(policy==null)continue;
                if(!advancePolicyRevision(id,policy))continue;
                String fingerprint=JSON.toJSONString(policy);
                if(fingerprint.equals(syncedPolicies.get(id)))continue;
                var response=WebSocketServer.sendConnectorMsg(id,policy,"OpenWrtDnsPolicy",10);
                if(response!=null&&"OK".equals(response.getMsg())){
                    syncedPolicies.put(id,fingerprint);
                    jdbc.update("UPDATE openwrt_dns_resolver SET sync_error=NULL WHERE connector_id=?",id);
                }
                else jdbc.update("UPDATE openwrt_dns_resolver SET sync_error=? WHERE connector_id=?",
                        StringUtils.abbreviate(response==null?"Agent 无响应":response.getMsg(),500),id);
            } catch(RuntimeException e){log.warn("OpenWrt DNS sync {}: {}",id,e.getMessage());}
        }
    }

    boolean advancePolicyRevision(Long id,Map<String,Object> policy) {
        Map<String,Object> content=new LinkedHashMap<>(policy);content.remove("revision");
        String hash;
        try {hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(content).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        long revision=((Number)policy.get("revision")).longValue();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT policy_hash AS policyHash,policy_revision AS revision FROM openwrt_dns_resolver WHERE connector_id=?",id);
        if(rows.isEmpty()||((Number)rows.get(0).get("revision")).longValue()!=revision)return false;
        String previous=Objects.toString(rows.get(0).get("policyHash"),null);
        if(hash.equals(previous))return true;
        long next=previous==null?revision:revision+1;
        int changed=jdbc.update("UPDATE openwrt_dns_resolver SET policy_hash=?,policy_revision=? WHERE connector_id=? AND policy_revision=?",hash,next,id,revision);
        if(changed!=1)return false;
        policy.put("revision",next);return true;
    }

    Map<String,Object> policy(long connectorId) {
        Map<String,Object> config = jdbc.query("SELECT interface_carriers AS interfaceCarriers,smart_entry_group_ids AS groupIds,"
                + "policy_revision AS revision FROM openwrt_dns_resolver WHERE connector_id=?", rs -> rs.next()?Map.of(
                        "interfaceCarriers",rs.getString("interfaceCarriers"),"groupIds",rs.getString("groupIds"),"revision",rs.getLong("revision")):null,connectorId);
        if(config==null)return null;
        Map<String,String> interfaces=parseObject(Objects.toString(config.get("interfaceCarriers"),"{}"));
        List<Long> ids=parseLongs(Objects.toString(config.get("groupIds"),"[]"));
        List<Map<String,Object>> groups=new ArrayList<>();
        for(Long id:ids){
            Map<String,Object> group=jdbc.query("SELECT id,domain,record_type AS recordType,ttl,switch_cooldown_ms FROM smart_entry_group WHERE id=? AND enabled=1",rs->{
                if(!rs.next())return null;Map<String,Object> value=new LinkedHashMap<>();value.put("id",rs.getLong("id"));value.put("domain",rs.getString("domain"));value.put("recordType",rs.getString("recordType"));value.put("ttl",5);value.put("cooldown",rs.getInt("switch_cooldown_ms"));return value;
            },id);
            if(group==null)continue;
            List<Map<String,Object>> routes=jdbc.queryForList("SELECT carrier,status,forward_id AS forwardId,entry_address AS entryAddress,"
                    + "current_forward_id AS currentForwardId,current_address AS currentAddress,fallback_carriers AS fallbackCarriers,last_switched_at AS lastSwitchedAt "
                    + "FROM smart_entry_route WHERE group_id=?",id);
            Map<String,String> addresses=new LinkedHashMap<>();String defaultAddress=null;
            for(Map<String,Object> route:routes){
                Map<String,Object> selected=SmartEntryService.chooseRoute(route,routes,true,((Number)group.getOrDefault("cooldown",0)).intValue(),System.currentTimeMillis());
                String address=selected==null?null:Objects.toString(selected.get("entryAddress"),null);
                String carrier=Objects.toString(route.get("carrier"));
                addresses.put(carrier,StringUtils.defaultString(address));
                if("default".equals(carrier))defaultAddress=address;
            }
            if(defaultAddress==null)defaultAddress="";
            group.remove("cooldown");group.put("defaultAddress",defaultAddress);group.put("addresses",addresses);groups.add(group);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("revision",config.get("revision"));result.put("defaultCarrier","default");
        result.put("interfaceCarriers",interfaces);result.put("groups",groups);
        Map<String,List<String>> cidrs=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT carrier,cidrs FROM source_ip_carrier_database WHERE cidr_count>0 ORDER BY carrier")) {
            cidrs.put(row.get("carrier").toString(),List.of(row.get("cidrs").toString().trim().split("\\s+")));
        }
        result.put("carrierCidrs",cidrs);return result;
    }

    private InternalConnector ownedResolver(Long id){
        if(id==null)return null;InternalConnector connector=connectors.selectById(id);
        if(connector==null||connector.getStatus()==null||connector.getStatus()!=1||!"openwrt_dns".equals(connector.getConnectorRole()))return null;
        Integer user=JwtUtil.getUserIdFromToken()==null?null:JwtUtil.getUserIdFromToken().intValue();
        boolean admin=Objects.equals(JwtUtil.getRoleIdFromToken(),0);
        return admin||Objects.equals(user,connector.getUserId())?connector:null;
    }

    private Map<String,String> normalizeInterfaces(Map<String,String> source){
        Map<String,String> result=new LinkedHashMap<>();if(source==null)return result;
        for(var entry:source.entrySet()){
            String iface=StringUtils.trimToEmpty(entry.getKey());String carrier=StringUtils.lowerCase(StringUtils.trimToEmpty(entry.getValue()),Locale.ROOT);
            if(iface.length()>15||!iface.matches("[A-Za-z0-9_.:-]{1,15}"))throw new IllegalArgumentException("WAN 接口名称无效："+iface);
            if(!Set.of("telecom","unicom","mobile").contains(carrier))throw new IllegalArgumentException("请选择电信、联通或移动线路");
            result.put(iface,carrier);
        }
        return result;
    }

    private List<Long> normalizeGroups(List<Long> source){
        if(source==null||source.isEmpty())throw new IllegalArgumentException("至少选择一个三网优化策略");
        if(source.size()>256)throw new IllegalArgumentException("最多绑定 256 个策略");
        Set<Long> ids=new HashSet<>(source);if(ids.contains(null)||ids.stream().anyMatch(id->id<=0))throw new IllegalArgumentException("三网优化策略编号无效");
        String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        List<Long> existing=jdbc.queryForList("SELECT id FROM smart_entry_group WHERE enabled=1 AND id IN ("+marks+")",Long.class,ids.toArray());
        if(existing.size()!=ids.size())throw new IllegalArgumentException("所选策略不存在或已停用");
        return new ArrayList<>(ids);
    }

    private Map<String,String> parseObject(String value){try{return JSON.parseObject(value,Map.class);}catch(Exception e){return new LinkedHashMap<>();}}
    private List<Long> parseLongs(String value){try{return JSON.parseArray(value,Long.class);}catch(Exception e){return new ArrayList<>();}}
}
