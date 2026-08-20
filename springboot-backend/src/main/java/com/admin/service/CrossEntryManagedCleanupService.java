package com.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Persists cleanup work outside the save transaction so failed remote cleanup can be retried. */
@Slf4j
@Service
public class CrossEntryManagedCleanupService {
    private final JdbcTemplate jdbcTemplate;

    public CrossEntryManagedCleanupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(List<Item> items, String reason) {
        if (items == null || items.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Item item : items) {
            if (item.forwardId() == null && !item.createdTunnel()) continue;
            jdbcTemplate.update("INSERT INTO cross_entry_managed_cleanup "
                            + "(group_id,forward_id,tunnel_id,entry_node_id,target_address,public_port,port_mode,protocol_mode,created_tunnel,reason,attempts,last_error,last_attempt_at,created_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,0,NULL,NULL,?)",
                    item.groupId(), item.forwardId(), item.tunnelId(), item.entryNodeId(), item.targetAddress(),
                    item.publicPort(), item.portMode(), item.protocolMode(), item.createdTunnel(), reason, now);
        }
    }

    public List<java.util.Map<String, Object>> listPending(int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        return jdbcTemplate.queryForList("SELECT id,group_id AS groupId,forward_id AS forwardId,tunnel_id AS tunnelId,"
                        + "entry_node_id AS entryNodeId,target_address AS targetAddress,public_port AS publicPort,"
                        + "port_mode AS portMode,protocol_mode AS protocolMode,created_tunnel AS createdTunnel,reason "
                        + "FROM cross_entry_managed_cleanup ORDER BY COALESCE(last_attempt_at,0),id LIMIT " + safeLimit);
    }

    public void markAttempt(long id) {
        jdbcTemplate.update("UPDATE cross_entry_managed_cleanup SET attempts=attempts+1,last_attempt_at=?,last_error=NULL WHERE id=?",
                System.currentTimeMillis(), id);
    }

    public void markFailed(long id, String error) {
        jdbcTemplate.update("UPDATE cross_entry_managed_cleanup SET last_error=?,last_attempt_at=? WHERE id=?",
                error, System.currentTimeMillis(), id);
    }

    public void markDone(long id) {
        jdbcTemplate.update("DELETE FROM cross_entry_managed_cleanup WHERE id=?", id);
    }

    public record Item(Long groupId, Long forwardId, Long tunnelId, Long entryNodeId,
                       String targetAddress, Integer publicPort, String portMode,
                       String protocolMode, boolean createdTunnel) {
    }
}
