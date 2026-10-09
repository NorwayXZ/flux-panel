package com.admin.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes group edits with customer port allocation until the database commit finishes. */
@Component
public class CrossEntryGroupMutationLocks {
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(long groupId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(groupId, ignored -> new ReentrantLock());
        lock.lock();
        return runLocked(lock, action);
    }

    /** Used while holding a grant lock to avoid waiting behind a migration that needs it. */
    public <T> T tryWithLock(long groupId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(groupId, ignored -> new ReentrantLock());
        if (!lock.tryLock()) return null;
        return runLocked(lock, action);
    }

    private <T> T runLocked(ReentrantLock lock, Supplier<T> action) {
        boolean releaseAfterCommit = TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive();
        if (releaseAfterCommit) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) { lock.unlock(); }
            });
        }
        try { return action.get(); }
        finally { if (!releaseAfterCommit) lock.unlock(); }
    }
}
