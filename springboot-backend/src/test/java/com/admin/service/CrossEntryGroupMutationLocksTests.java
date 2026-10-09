package com.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CrossEntryGroupMutationLocksTests {
    @Test
    void customerPortAllocationWaitsForTheGroupCommit() throws Exception {
        CrossEntryGroupMutationLocks locks = new CrossEntryGroupMutationLocks();
        var worker = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        boolean committed = false;
        try {
            assertEquals("saved", locks.withLock(7L, () -> "saved"));
            assertNull(worker.submit(() -> locks.tryWithLock(7L, () -> "allocated"))
                    .get(2, TimeUnit.SECONDS));
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            committed = true;
            assertEquals("allocated", worker.submit(() -> locks.tryWithLock(7L, () -> "allocated"))
                    .get(2, TimeUnit.SECONDS));
        } finally {
            if (!committed) TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            worker.shutdownNow();
        }
    }
}
