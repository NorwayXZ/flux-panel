package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.YearMonth;
import java.util.*;

/** Routes only authenticated payment requests. Proxy traffic stays on each node. */
@Service
public class MysteriumSharedBillingService implements ApplicationRunner {
    static final String RELEASE = "https://github.com/NorwayXZ/flux-panel/releases/download/myst-shared-1.39.7-3/";
    private final JdbcTemplate jdbc;
    private final NodeMapper nodes;
    private final MysteriumService mysterium;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public MysteriumSharedBillingService(JdbcTemplate jdbc, NodeMapper nodes, MysteriumService mysterium) {
        this.jdbc = jdbc; this.nodes = nodes; this.mysterium = mysterium;
    }

    @Override public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS mysterium_shared_pool (id int PRIMARY KEY, owner_node_id bigint NOT NULL, "
                + "consumer varchar(64) NOT NULL, endpoint varchar(1000) NOT NULL, enabled tinyint NOT NULL DEFAULT 0, "
                + "created_time bigint NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mysterium_shared_member (node_id bigint PRIMARY KEY, token_hash varchar(64) NOT NULL, "
                + "state varchar(20) NOT NULL, last_error varchar(500) DEFAULT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public Map<String,Object> overview() {
        List<Map<String,Object>> pools = jdbc.queryForList("SELECT * FROM mysterium_shared_pool WHERE id=1");
        Map<String,Object> result = new LinkedHashMap<>();
        if (!pools.isEmpty()) result.putAll(pools.get(0));
        result.put("members", jdbc.queryForList("SELECT node_id,state,last_error FROM mysterium_shared_member"));
        return result;
    }

    public synchronized R configure(Long ownerId, List<Long> memberIds, String endpoint) {
        if (ownerId == null || memberIds == null || memberIds.isEmpty() || memberIds.stream().anyMatch(Objects::isNull)) return R.err("请选择付款账户节点和共享节点");
        URI uri;
        try { uri=URI.create(endpoint); } catch (Exception e) { return R.err("付款地址无效"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || !"/api/v1/mysterium-shared/pay".equals(uri.getPath()) || uri.getQuery()!=null)
            return R.err("付款地址必须为面板 HTTPS 地址加 /api/v1/mysterium-shared/pay");
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT * FROM mysterium_shared_pool WHERE id=1");
        if (!existing.isEmpty() && !Objects.equals(existing.get(0).get("endpoint"), endpoint)) return R.err("已有共享节点使用原付款地址，请保持地址不变");
        if (!existing.isEmpty() && ((Number)existing.get(0).get("owner_node_id")).longValue()!=ownerId)
            return R.err("共享账户已绑定另一付款节点，不能直接替换资金账户");
        Node owner=nodes.selectById(ownerId);
        if(owner==null || !WebSocketServer.isNodeOnline(ownerId)) return R.err("付款节点离线或不存在");
        if (!AgentVersionUtil.isAtLeast(owner.getVersion(), "2.84.0")) return R.err("付款节点需要升级至 Agent 2.84.0-beta.1 或更高版本");
        List<Long> targets = new ArrayList<>(new LinkedHashSet<>(memberIds));
        if (targets.size()>100) return R.err("一次最多绑定100台节点");
        for(Long id:targets) {
 Node member=nodes.selectById(id);
 if(member==null || !WebSocketServer.isNodeOnline(id)) return R.err("共享节点离线或不存在："+id);
 if(!AgentVersionUtil.isAtLeast(member.getVersion(),"2.84.0")) return R.err("共享节点需要升级至 Agent 2.84.0-beta.1："+member.getName());
 }
        try {
            Map<String,Object> assets=assets();
            R refreshed=mysterium.refresh(ownerId);
            if(refreshed.getCode()!=0) return refreshed;
            JSONObject account=JSON.parseObject(JSON.toJSONString(refreshed.getData()));
            String consumer=account.getString("identity");
            if(consumer==null || !consumer.matches("0x[0-9a-fA-F]{40}"))return R.err("付款节点没有有效账户");
            if(account.getDoubleValue("balance")<=0)return R.err("请先给付款节点充值");
            Map<String,Object> signer=new LinkedHashMap<>(Map.of("role","signer","consumer",consumer,"binaryAssets",assets));
            // Reconfigure the signer binary while retaining its durable payment ledger.
            command(ownerId,"MysteriumBillingConfigure",signer,240);
            jdbc.update("INSERT INTO mysterium_shared_pool(id,owner_node_id,consumer,endpoint,enabled,created_time) VALUES(1,?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE endpoint=VALUES(endpoint),enabled=1",ownerId,consumer,endpoint,1,System.currentTimeMillis());
            String passphrase=randomToken();
            JSONObject exported=command(ownerId,"MysteriumBillingExport",Map.of("passphrase",passphrase),60);
            List<Map<String,Object>> results=new ArrayList<>();
            for(Long id:targets) {
                if(id.equals(ownerId))continue;
                String token=randomToken();
                // Bound nodes are configured again too, so a rebinding installs the current client everywhere.
                jdbc.update("INSERT INTO mysterium_shared_member(node_id,token_hash,state) VALUES(?,?,'configuring') "
                        + "ON DUPLICATE KEY UPDATE token_hash=VALUES(token_hash),state='configuring',last_error=NULL",id,hash(token));
                try {
                    Map<String,Object> request=new LinkedHashMap<>(Map.of("role","consumer","consumer",consumer,
                            "endpoint",endpoint,"token",token,"binaryAssets",assets,
                            "identityData",exported.getString("identityData"),"passphrase",passphrase));
                    command(id,"MysteriumBillingConfigure",request,240);
                    jdbc.update("UPDATE mysterium_shared_member SET state='ready' WHERE node_id=?",id);
                    // The shared identity is recorded through the existing status path.
                    R setup=mysterium.setup(id);
                    if(setup.getCode()!=0)throw new IllegalStateException(setup.getMsg());
                    results.add(Map.of("nodeId",id,"success",true));
                } catch(Exception e) {
                    String error=safeError(e);
                    jdbc.update("UPDATE mysterium_shared_member SET state='failed',last_error=? WHERE node_id=?",error,id);
                    results.add(Map.of("nodeId",id,"success",false,"error",error));
                }
            }
            return R.ok(Map.of("pool",overview(),"results",results));
        } catch(Exception e) { return R.err("配置共享付款失败："+safeError(e)); }
    }

    public JSONObject pay(String token, JSONObject request) {
        if(token==null || token.length()<32 || token.length()>256)throw new IllegalArgumentException("unauthorized");
        List<Map<String,Object>> members=jdbc.queryForList("SELECT * FROM mysterium_shared_member WHERE token_hash=? AND state IN ('ready','configuring')",hash(token));
        if(members.size()!=1)throw new IllegalArgumentException("unauthorized");
        List<Map<String,Object>> pools=jdbc.queryForList("SELECT * FROM mysterium_shared_pool WHERE id=1 AND enabled=1");
        if(pools.isEmpty())throw new IllegalStateException("shared pool disabled");
        Map<String,Object> pool=pools.get(0);
        if(!Objects.toString(pool.get("consumer"),"").equalsIgnoreCase(request.getString("Consumer")))
            throw new IllegalArgumentException("wrong shared consumer");
        Long owner=((Number)pool.get("owner_node_id")).longValue();
        Long member=((Number)members.get(0).get("node_id")).longValue();
        if(!WebSocketServer.isNodeOnline(member))throw new IllegalArgumentException("member offline");
        List<Map<String,Object>> accounts=jdbc.queryForList("SELECT suspended,month,month_spent FROM mysterium_account WHERE node_id=?",owner);
        Map<String,Object> settings=mysterium.settings();
        if(!Boolean.TRUE.equals(settings.get("enabled")))throw new IllegalStateException("Mysterium disabled");
        if(accounts.isEmpty() || ((Number)accounts.get(0).get("suspended")).intValue()==1)throw new IllegalStateException("shared account suspended");
        Map<String,Object> account=accounts.get(0);
        if(((Number)settings.get("monthlyBudgetUsd")).doubleValue()>0 && YearMonth.now(MysteriumService.ZONE).toString().equals(account.get("month"))
                && ((Number)account.get("month_spent")).doubleValue()*((Number)settings.get("mystUsd")).doubleValue()
                >= ((Number)settings.get("monthlyBudgetUsd")).doubleValue())throw new IllegalStateException("shared monthly budget exceeded");
        JSONObject signed=command(owner,"MysteriumBillingPay",Map.of("payload",request.toJSONString()),35);
        return JSON.parseObject(signed.getString("signedPayload"));
    }

    public Long ownerFor(Long nodeId) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT p.owner_node_id FROM mysterium_shared_pool p "
                + "JOIN mysterium_shared_member m ON m.node_id=? AND m.state='ready' WHERE p.id=1 AND p.enabled=1",nodeId);
        return rows.isEmpty()?nodeId:((Number)rows.get(0).get("owner_node_id")).longValue();
    }

    private Map<String,Object> assets() throws Exception {
        HttpResponse<String> response=http.send(HttpRequest.newBuilder(URI.create(RELEASE+"SHA256SUMS")).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("共享客户端发行文件尚不可用");
        Map<String,Object> result=new LinkedHashMap<>();
        for(String line:response.body().split("\\R")) {
            String[] parts=line.trim().split("\\s+");
            if(parts.length!=2 || !parts[0].matches("[a-fA-F0-9]{64}"))continue;
            String name=parts[1].replaceFirst("^\\*","");
            if(!name.matches("myst-shared-(linux|darwin)-(amd64|arm64)"))continue;
            result.put(name.substring("myst-shared-".length()),Map.of("url",RELEASE+name,"sha256",parts[0]));
        }
        if(result.isEmpty())throw new IllegalStateException("发行校验清单为空");
        return result;
    }

    private JSONObject command(Long id,String type,Object body,int timeout) {
        GostDto response=WebSocketServer.send_msg(id,body,type,timeout);
        if(response==null || !"OK".equals(response.getMsg()))throw new IllegalStateException(response==null?"节点无响应":response.getMsg());
        return JSON.parseObject(JSON.toJSONString(response.getData()));
    }
    private static String randomToken(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String safeError(Exception e){String value=Objects.toString(e.getMessage(),"节点操作失败");return value.substring(0,Math.min(450,value.length()));}
}
