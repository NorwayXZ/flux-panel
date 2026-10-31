package com.admin.service;

import com.admin.entity.Node;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentUpgradeBatchReconciliationTests {
    @Test void laterSuccessfulRetryRepairsOldBatchButOfflineOrOldNodesStayFailed() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE agent_upgrade_task(task_id VARCHAR PRIMARY KEY,batch_id VARCHAR,node_id BIGINT,target_version VARCHAR,state VARCHAR,message VARCHAR,updated_at BIGINT,finished_at BIGINT)");
        jdbc.execute("CREATE TABLE agent_upgrade_batch(batch_id VARCHAR PRIMARY KEY,total_nodes INT,state VARCHAR,completed_nodes INT,message VARCHAR,updated_at BIGINT,finished_at BIGINT)");
        jdbc.update("INSERT INTO agent_upgrade_batch(batch_id,total_nodes,state) VALUES ('batch',1,'completed_with_errors')");
        jdbc.update("INSERT INTO agent_upgrade_task(task_id,batch_id,node_id,target_version,state) VALUES ('old','batch',1,'2.84.0','failed')");
        jdbc.update("INSERT INTO agent_upgrade_task(task_id,node_id,target_version,state) VALUES ('retry',1,'2.84.0','success')");
        var service = spy(new AgentUpgradeService(mock(NodeService.class), jdbc));
        var node = new Node(); node.setId(1L); node.setVersion("2.84.0");
        doReturn(false).when(service).isNodeOnline(1L);
        service.reconcileBatchTasks(List.of(node));
        assertEquals("failed", jdbc.queryForObject("SELECT state FROM agent_upgrade_task WHERE task_id='old'", String.class));
        doReturn(true).when(service).isNodeOnline(1L);
        node.setVersion("2.83.0"); service.reconcileBatchTasks(List.of(node));
        assertEquals("completed_with_errors", jdbc.queryForObject("SELECT state FROM agent_upgrade_batch", String.class));
        node.setVersion("2.84.0"); service.reconcileBatchTasks(List.of(node));
        assertEquals("success", jdbc.queryForObject("SELECT state FROM agent_upgrade_batch", String.class));
        assertEquals("success", jdbc.queryForObject("SELECT state FROM agent_upgrade_task WHERE task_id='old'", String.class));
    }
}
