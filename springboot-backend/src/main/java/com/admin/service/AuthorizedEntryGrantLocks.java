package com.admin.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthorizedEntryGrantLocks {
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    public Object forGrant(Long grantId) {
        return locks.computeIfAbsent(grantId, ignored -> new Object());
    }
}
