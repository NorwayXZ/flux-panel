package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.admin.common.lang.R;
import com.admin.config.AiProposalSchemaInitializer;
import com.alibaba.fastjson2.JSONObject;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiOpsProposalServiceTests {
  JdbcTemplate db;
  ForwardService forwards;
  AiOpsProposalService service;

  @BeforeEach
  void setup() {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:mem:proposal"
            + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    db = new JdbcTemplate(source);
    new AiProposalSchemaInitializer(db).init();
    db.execute("CREATE TABLE forward(id bigint PRIMARY KEY,name varchar(200))");
    db.update("INSERT INTO forward VALUES(7,'测试转发')");
    forwards = mock(ForwardService.class);
    service = new AiOpsProposalService(db, forwards);
  }

  @Test
  void validationRejectsUnknownActionAndBlankReason() {
    assertNotNull(AiOpsProposalService.validate("delete_forward", 7, "标题", "理由"));
    assertNotNull(AiOpsProposalService.validate("pause_forward", 0, "标题", "理由"));
    assertNotNull(AiOpsProposalService.validate("pause_forward", 7, " ", "理由"));
    assertNotNull(AiOpsProposalService.validate("pause_forward", 7, "标题", ""));
    assertNull(AiOpsProposalService.validate("pause_forward", 7, "标题", "理由"));
  }

  @Test
  void proposeStoresPendingAndRejectsDuplicatesAndMissingTargets() {
    Object created = service.propose(1, "chat", "pause_forward", 7, "暂停异常转发", "落地连续超时");
    JSONObject view = (JSONObject) created;
    assertEquals("pending", view.getString("status"));
    assertEquals("测试转发", view.getString("targetName"));
    assertEquals("暂停转发", view.getString("actionLabel"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.propose(1, "chat", "pause_forward", 7, "重复", "重复理由"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.propose(1, "chat", "resume_forward", 99, "不存在", "理由"));
    verifyNoInteractions(forwards);
  }

  @Test
  void approveExecutesThroughForwardServiceOnce() {
    JSONObject created =
        (JSONObject) service.propose(1, "chat", "pause_forward", 7, "暂停", "理由");
    long id = created.getLongValue("id");
    when(forwards.pauseForward(7L)).thenReturn(R.ok("已暂停"));

    R approved = service.approve(id, 2);
    assertEquals(0, approved.getCode());
    JSONObject view = (JSONObject) approved.getData();
    assertEquals("executed", view.getString("status"));
    assertEquals("已暂停", view.getString("result"));
    assertEquals(2L, view.getLongValue("decidedBy"));

    R again = service.approve(id, 2);
    assertNotEquals(0, again.getCode());
    verify(forwards, times(1)).pauseForward(7L);
  }

  @Test
  void approveRecordsFailureWithoutClaimingSuccess() {
    JSONObject created =
        (JSONObject) service.propose(1, "task", "resume_forward", 7, "恢复", "理由");
    long id = created.getLongValue("id");
    when(forwards.resumeForward(7L)).thenReturn(R.err("该转发由多线路并发调度托管"));

    R approved = service.approve(id, 2);
    assertNotEquals(0, approved.getCode());
    var row = db.queryForMap("SELECT status,result FROM ai_ops_proposal WHERE id=?", id);
    assertEquals("failed", row.get("status"));
    assertTrue(String.valueOf(row.get("result")).contains("托管"));
  }

  @Test
  void rejectDoesNotExecute() {
    JSONObject created =
        (JSONObject) service.propose(1, "chat", "pause_forward", 7, "暂停", "理由");
    R rejected = service.reject(created.getLongValue("id"), 2);
    assertEquals(0, rejected.getCode());
    assertEquals("rejected", ((JSONObject) rejected.getData()).getString("status"));
    verifyNoInteractions(forwards);
  }
}
