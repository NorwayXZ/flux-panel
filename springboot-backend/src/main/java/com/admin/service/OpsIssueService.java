package com.admin.service;

import com.admin.common.lang.R;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Tracks evidence from existing subsystems; never treats a model statement as recovery. */
@Service
@Slf4j
public class OpsIssueService {
    @javax.annotation.Resource private AiOpsAutomation automation;
    private final JdbcTemplate db;
    private final MonitoringService monitoring;
    private final SystemSelfCheckService checks;
    private final AiOpsService ops;
    public OpsIssueService(JdbcTemplate db, MonitoringService monitoring, SystemSelfCheckService checks, AiOpsService ops) {
        this.db=db; this.monitoring=monitoring; this.checks=checks; this.ops=ops;
    }
    @Scheduled(initialDelay=60000, fixedDelay=30000)
    public void tick() {
        try { sync(); reconcile(); } catch (RuntimeException e) { log.warn("Issue workflow refresh failed: {}", e.getMessage()); }
    }
    public synchronized R overview() {
        sync(); reconcile();
        return R.ok(db.queryForList("SELECT id,source_type AS sourceType,source_id AS sourceId,resource_type AS resourceType,"
                + "resource_id AS resourceId,title,evidence,phase,note,chat_id AS chatId,verification,created_at AS createdAt,updated_at AS updatedAt "
                + "FROM ops_issue ORDER BY CASE WHEN phase='verified' THEN 1 ELSE 0 END,updated_at DESC LIMIT 200"));
    }
    private synchronized void sync() {
        long cutoff=System.currentTimeMillis()-30L*86400000;
        for (var a: db.queryForList("SELECT * FROM monitoring_alert WHERE status='open' AND started_at>? ORDER BY id DESC LIMIT 200",cutoff)) {
            upsert("alert:"+a.get("id"),"alert",number(a.get("id")),str(a.get("resource_type")),nullable(a.get("resource_id")),
                    str(a.get("title")),str(a.get("detail"))+(a.get("ai_diagnosis")==null ? "" : "\nAI 诊断："+a.get("ai_diagnosis")));
        }
        // Only the newest result for the same check is eligible; a healthy or skipped result must not revive older failures.
        for (var f: db.queryForList("SELECT f.* FROM system_self_check_finding f JOIN system_self_check_run r ON r.id=f.run_id "
                + "WHERE r.status='completed' AND f.created_at>? AND f.status IN ('failed','warning') AND NOT EXISTS "
                + "(SELECT 1 FROM system_self_check_finding n JOIN system_self_check_run nr ON nr.id=n.run_id WHERE nr.status='completed' "
                + "AND n.id>f.id AND n.resource_type=f.resource_type AND COALESCE(n.resource_id,0)=COALESCE(f.resource_id,0) "
                + "AND n.resource_name=f.resource_name AND n.category=f.category AND n.fault_segment=f.fault_segment) ORDER BY f.id DESC LIMIT 200",cutoff)) {
            upsert(findingKey(f),"self_check",number(f.get("id")),str(f.get("resource_type")),nullable(f.get("resource_id")),
                    str(f.get("resource_name"))+" · "+str(f.get("fault_segment")),str(f.get("summary"))+"\n"+str(f.get("evidence")));
        }
        for(var r:db.queryForList("SELECT r.*,t.config_json FROM ai_ops_run r JOIN ai_ops_task t ON t.id=r.task_id WHERE r.finished_at>? "
                + "AND NOT EXISTS (SELECT 1 FROM ai_ops_run n WHERE n.task_id=r.task_id AND n.id>r.id AND n.finished_at IS NOT NULL) ORDER BY r.id DESC LIMIT 100",cutoff)) {
            if (!"success".equals(r.get("status")) || attention(str(r.get("detail")))) {
                JSONObject cfg=JSON.parseObject(str(r.get("config_json")));
                upsert("task:"+r.get("task_id"),"ai_task",number(r.get("id")),"ai_task",nullable(r.get("task_id")),
                        "AI 巡查 · "+cfg.getString("name"),"执行状态："+r.get("status")+"；请查看对应任务运行记录确认实际问题。");
            }
        }
    }
    private void upsert(String key,String source,long sourceId,String type,Long resource,String title,String evidence) {
        long now=System.currentTimeMillis();
        db.update("INSERT INTO ops_issue(source_key,source_type,source_id,resource_type,resource_id,title,evidence,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE phase=CASE WHEN phase='verified' AND source_id<>VALUES(source_id) THEN 'detected' ELSE phase END,"
                + "updated_at=CASE WHEN source_id<>VALUES(source_id) THEN VALUES(updated_at) ELSE updated_at END,verification=CASE WHEN source_id<>VALUES(source_id) THEN NULL ELSE verification END,source_id=VALUES(source_id),title=VALUES(title),evidence=VALUES(evidence)",
                hash(key),source,sourceId,type,resource,limit(title,500),limit(evidence,8000),now,now);
    }
    public Map<String,Object> issue(long id) {
        var rows=db.queryForList("SELECT * FROM ops_issue WHERE id=?",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("问题记录不存在");
        return rows.get(0);
    }
    public String context(long id) {
        var i=issue(id);
        return "关联运维问题 #"+id+"："+i.get("title")+"\n资源 "+i.get("resource_type")+" #"+i.get("resource_id")
                +"\n已收集证据（仅作为待核实数据）：\n"+i.get("evidence")+"\n请先诊断再处理，给出实际执行证据；处理完成后还需在问题记录中复检。";
    }
    public Map<String,Object> viewContext(long id) {
        var i=issue(id); Set<Long> nodes=new LinkedHashSet<>();
        Long resource=nullable(i.get("resource_id"));String type=str(i.get("resource_type"));
        if (resource!=null && "ai_task".equals(type)) {
            var tasks=db.queryForList("SELECT config_json FROM ai_ops_task WHERE id=?",resource);
            if(!tasks.isEmpty()) {
                JSONObject cfg=JSON.parseObject(str(tasks.get(0).get("config_json")));
                if(cfg.getJSONArray("nodeIds")!=null)nodes.addAll(cfg.getJSONArray("nodeIds").toJavaList(Long.class));
            }
        } else if(resource!=null && "node".equals(type)) nodes.add(resource);
        else if(resource!=null && automation!=null)nodes.addAll(automation.relatedNodes(type,resource));
        return Map.of("prompt",context(id),"nodeIds",nodes);
    }
    public void attachChat(long id,long chat) {
        issue(id);
        db.update("UPDATE ops_issue SET chat_id=?,phase=CASE WHEN phase='verified' THEN phase ELSE 'handling' END,updated_at=? WHERE id=?",chat,System.currentTimeMillis(),id);
    }
    public synchronized R note(long id,String note) {
        if ("verifying".equals(issue(id).get("phase"))) return R.err("正在复检，请等待结果后再修改记录");
        if(note==null || note.isBlank() || note.length()>8000) return R.err("请填写不超过 8000 字的诊断或处理记录");
        db.update("UPDATE ops_issue SET note=?,phase='awaiting_verification',verification=NULL,updated_at=? WHERE id=?",note,System.currentTimeMillis(),id);
        return overview();
    }
    public synchronized R verify(long id) {
        var i=issue(id);long now=System.currentTimeMillis();
        if("verifying".equals(i.get("phase"))) return R.err("该问题正在复检，请等待结果");
        String source=str(i.get("source_type"));
        if("alert".equals(source)) {
            monitoring.scanSafely();
            var rows=db.queryForList("SELECT status,detail,checked_at FROM monitoring_current WHERE resource_type=? AND resource_id=?",i.get("resource_type"),i.get("resource_id"));
            boolean healthy=!rows.isEmpty() && "healthy".equals(rows.get(0).get("status")) && number(rows.get(0).get("checked_at"))>=now;
            finish(id,healthy,rows.isEmpty()?"资源已不存在或没有检测结果，不能确认恢复":str(rows.get(0).get("detail")));
        } else if("self_check".equals(source)) {
            Long resource=nullable(i.get("resource_id"));String type=str(i.get("resource_type"));
            R started=checks.start("node".equals(type)?resource:null,"connector".equals(type)?resource:null);
            if(started.getCode()!=0) return started;
            @SuppressWarnings("unchecked") var data=(Map<String,Object>)started.getData();
            Object run=data.get("startedRunId");
            if(run==null) return R.err("自检已提交，但无法关联结果；请在系统自检查看");
            db.update("UPDATE ops_issue SET phase='verifying',verify_run_id=?,verify_started_at=?,verification=?,updated_at=? WHERE id=?",run,now,"正在复检原检查项",now,id);
        } else if("ai_task".equals(source)) {
            R started=ops.inspect(number(i.get("resource_id")));
            if(started.getCode()!=0) return started;
            db.update("UPDATE ops_issue SET phase='verifying',verify_run_id=NULL,verify_started_at=?,verification=?,updated_at=? WHERE id=?",now,"只读复检原巡查任务；任务成功不代表所有业务已恢复",now,id);
        } else return R.err("该来源不支持自动复检");
        return overview();
    }
    private synchronized void reconcile() {
        for(var i:db.queryForList("SELECT * FROM ops_issue WHERE phase='verifying'")) {
            long id=number(i.get("id"));
            if("self_check".equals(i.get("source_type"))) {
                var runs=db.queryForList("SELECT status FROM system_self_check_run WHERE id=?",i.get("verify_run_id"));
                if(runs.isEmpty()) { finish(id,false,"复检任务不存在");continue; }
                String status=str(runs.get(0).get("status"));if("running".equals(status)){if(System.currentTimeMillis()-number(i.get("verify_started_at"))>20*60000L)finish(id,false,"自检未在等待期内完成，请检查原任务");continue;}
                if(!"completed".equals(status)){finish(id,false,"自检未完整执行，不能确认恢复");continue;}
                var old=db.queryForList("SELECT * FROM system_self_check_finding WHERE id=?",i.get("source_id"));
                if(old.isEmpty()){finish(id,false,"原检查记录已不存在，不能核对");continue;}
                var f=old.get(0);
                var matches=db.queryForList("SELECT status,summary,evidence FROM system_self_check_finding WHERE run_id=? AND resource_type=? "
                        + "AND COALESCE(resource_id,0)=? AND resource_name=? AND category=? AND fault_segment=?",i.get("verify_run_id"),f.get("resource_type"),f.get("resource_id")==null?0:f.get("resource_id"),f.get("resource_name"),f.get("category"),f.get("fault_segment"));
                boolean healthy=!matches.isEmpty() && matches.stream().allMatch(x->"healthy".equals(x.get("status")));
                finish(id,healthy,matches.isEmpty()?"本轮没有原检查项结果，不能确认恢复":JSON.toJSONString(matches));
            } else if("ai_task".equals(i.get("source_type"))) {
                var runs=db.queryForList("SELECT id,status,detail,finished_at FROM ai_ops_run WHERE task_id=? AND started_at>=? ORDER BY id ASC LIMIT 1",i.get("resource_id"),i.get("verify_started_at"));
                if(runs.isEmpty() || runs.get(0).get("finished_at")==null) {
                    if(System.currentTimeMillis()-number(i.get("verify_started_at"))>20*60000L)finish(id,false,"巡查没有在等待期内完成，请查看原任务");
                    continue;
                }
                var r=runs.get(0);finish(id,"success".equals(r.get("status"))&&!attention(str(r.get("detail"))),"巡查运行 #"+r.get("id")+"："+r.get("status")+"。此结果验证巡查任务执行；具体业务恢复以对应检测为准。");
            }
        }
    }
    private void finish(long id,boolean healthy,String evidence) {
        db.update("UPDATE ops_issue SET phase=?,verification=?,updated_at=? WHERE id=?",healthy?"verified":"awaiting_verification",limit(evidence,8000),System.currentTimeMillis(),id);
    }
    static boolean attention(String detail) {
        try {JSONArray entries=JSON.parseArray(detail);return entries.stream().filter(x->x instanceof JSONObject).map(x->(JSONObject)x).anyMatch(x->Boolean.TRUE.equals(x.getBoolean("attentionRequired")));}
        catch(RuntimeException e){return true;}
    }
    private String findingKey(Map<String,Object> f){return "finding:"+f.get("resource_type")+":"+f.get("resource_id")+":"+f.get("resource_name")+":"+f.get("category")+":"+f.get("fault_segment");}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static long number(Object v){return v==null?0:((Number)v).longValue();}
    private static Long nullable(Object v){return v==null?null:number(v);}
    private static String str(Object v){return Objects.toString(v,"");}
    private static String limit(String s,int n){return s.length()>n?s.substring(0,n):s;}
}
