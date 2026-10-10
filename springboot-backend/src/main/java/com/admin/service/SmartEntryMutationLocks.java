package com.admin.service;

import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Lock order: resource mutations, then a group lock, then a database transaction. */
@Component
public class SmartEntryMutationLocks {
    private final ReentrantLock lock = new ReentrantLock(true);

    public <T> T withLock(Supplier<T> action) {
        lock.lock();
        boolean defer = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
        if (defer) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { lock.unlock(); }
        });
        try { return action.get(); }
        finally { if (!defer) lock.unlock(); }
    }
}
