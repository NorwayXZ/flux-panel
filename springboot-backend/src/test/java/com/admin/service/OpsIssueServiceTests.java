package com.admin.service;

import com.admin.common.lang.R;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OpsIssueServiceTests {
    JdbcTemplate db; MonitoringService monitoring; SystemSelfCheckService checks; AiOpsService ops; OpsIssueService service;
    @BeforeEach void setup(){
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:issues"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);monitoring=mock(MonitoringService.class);checks=mock(SystemSelfCheckService.class);ops=mock(AiOpsService.class);service=new OpsIssueService(db,monitoring,checks,ops);
        db.execute("CREATE TABLE ops_issue(id BIGINT AUTO_INCREMENT PRIMARY KEY,source_key VARCHAR(64) UNIQUE,source_type VARCHAR(24),source_id BIGINT,resource_type VARCHAR(32),resource_id BIGINT,title VARCHAR(500),evidence TEXT,phase VARCHAR(32) DEFAULT 'detected',note TEXT,chat_id BIGINT,verify_run_id BIGINT,verify_started_at BIGINT,verification TEXT,created_at BIGINT,updated_at BIGINT)");
        db.execute("CREATE TABLE monitoring_alert(id BIGINT PRIMARY KEY,resource_type VARCHAR(32),resource_id BIGINT,title VARCHAR(500),detail TEXT,status VARCHAR(24),started_at BIGINT,ai_diagnosis TEXT)");
        db.execute("CREATE TABLE monitoring_current(resource_type VARCHAR(32),resource_id BIGINT,status VARCHAR(24),detail TEXT,checked_at BIGINT)");
        db.execute("CREATE TABLE system_self_check_run(id BIGINT PRIMARY KEY,status VARCHAR(24))");
        db.execute("CREATE TABLE system_self_check_finding(id BIGINT PRIMARY KEY,run_id BIGINT,resource_type VARCHAR(32),resource_id BIGINT,resource_name VARCHAR(100),category VARCHAR(32),fault_segment VARCHAR(120),status VARCHAR(24),summary TEXT,evidence TEXT,created_at BIGINT)");
        db.execute("CREATE TABLE ai_ops_task(id BIGINT PRIMARY KEY,config_json TEXT)");
        db.execute("CREATE TABLE ai_ops_run(id BIGINT PRIMARY KEY,task_id BIGINT,status VARCHAR(24),detail TEXT,started_at BIGINT,finished_at BIGINT)");
    }
    long issue(String source,long sourceId,String type,long resource){
        db.update("INSERT INTO ops_issue(source_key,source_type,source_id,resource_type,resource_id,title,evidence,created_at,updated_at) VALUES(?,?,?,?,?,'test','evidence',0,0)",UUID.randomUUID().toString(),source,sourceId,type,resource);
        return db.queryForObject("SELECT MAX(id) FROM ops_issue",Long.class);
    }
    String phase(long id){return db.queryForObject("SELECT phase FROM ops_issue WHERE id=?",String.class,id);}
    @Test void noteAndAiChatCannotMarkIssueVerified(){
        long id=issue("alert",1,"node",2);service.attachChat(id,10);assertEquals("handling",phase(id));
        assertEquals(0,service.note(id,"模型说已修复，等待检测证据").getCode());assertEquals("awaiting_verification",phase(id));
    }
    @Test void alertVerificationRequiresFreshHealthyStateAndDoesNotAcceptPausedOrMissing(){
        long id=issue("alert",1,"node",2);
        service.verify(id);assertEquals("awaiting_verification",phase(id));
        db.update("INSERT INTO monitoring_current VALUES('node',2,'healthy','old',1)");service.verify(id);assertEquals("awaiting_verification",phase(id));
        doAnswer(call->{db.update("UPDATE monitoring_current SET status='paused',checked_at=?",System.currentTimeMillis());return null;}).when(monitoring).scanSafely();
        service.verify(id);assertEquals("awaiting_verification",phase(id));
        doAnswer(call->{db.update("UPDATE monitoring_current SET status='healthy',detail='Agent online',checked_at=?",System.currentTimeMillis());return null;}).when(monitoring).scanSafely();
        service.verify(id);assertEquals("verified",phase(id));
    }
    @Test void exactCheckMustBeHealthyAndSkippedOrAbsentCannotCloseIssue(){
        db.update("INSERT INTO system_self_check_run VALUES(1,'completed')");
        db.update("INSERT INTO system_self_check_run VALUES(2,'running')");
        db.update("INSERT INTO system_self_check_finding VALUES(1,1,'node',7,'N','system','disk','failed','full','evidence',?)",System.currentTimeMillis());
        long id=issue("self_check",1,"node",7);
        when(checks.start(7L,null)).thenReturn(R.ok(new HashMap<>(Map.of("startedRunId",2L))));
        service.verify(id);assertEquals("verifying",phase(id));
        db.update("UPDATE system_self_check_run SET status='completed' WHERE id=2");service.overview();assertEquals("awaiting_verification",phase(id));
        db.update("INSERT INTO system_self_check_finding VALUES(2,2,'node',7,'N','system','disk','skipped','unknown','offline',?)",System.currentTimeMillis());
        service.verify(id);assertEquals("awaiting_verification",phase(id));
        db.update("UPDATE system_self_check_finding SET status='healthy',evidence='disk 40%' WHERE id=2");service.verify(id);assertEquals("verified",phase(id));
    }
    @Test void newerHealthyCheckPreventsOldFailureFromReappearing(){
        db.update("INSERT INTO system_self_check_run VALUES(1,'completed')");
        long now=System.currentTimeMillis();
        db.update("INSERT INTO system_self_check_finding VALUES(1,1,'node',7,'N','system','disk','failed','full','evidence',?)",now);
        service.overview();assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ops_issue",Integer.class));
        db.update("UPDATE ops_issue SET phase='verified'");
        db.update("INSERT INTO system_self_check_finding VALUES(2,1,'node',7,'N','system','disk','healthy','fine','evidence',?)",now);
        service.overview();assertEquals("verified",phase(1));
        db.update("INSERT INTO system_self_check_finding VALUES(3,1,'node',7,'N','system','disk','failed','full again','evidence',?)",now);
        service.overview();assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ops_issue",Integer.class));assertEquals("detected",phase(1));
    }
    @Test void aiTaskRecheckUsesReadOnlyInspectionAndNeedsCompletedEvidence(){
        db.update("INSERT INTO ai_ops_task VALUES(4,'{\"name\":\"巡查\"}')");
        long id=issue("ai_task",1,"ai_task",4);when(ops.inspect(4)).thenReturn(R.ok());
        service.verify(id);assertEquals("verifying",phase(id));verify(ops).inspect(4);verify(ops,never()).run(anyLong());
        long start=db.queryForObject("SELECT verify_started_at FROM ops_issue WHERE id=?",Long.class,id);
        db.update("INSERT INTO ai_ops_run VALUES(2,4,'success','[{\"attentionRequired\":true}]',?,?)",start,start+1);
        service.overview();assertEquals("awaiting_verification",phase(id));
    }
}
