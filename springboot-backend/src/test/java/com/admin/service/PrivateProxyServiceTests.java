package com.admin.service;

import com.admin.entity.PrivateProxy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateProxyServiceTests {

    @Test
    void assignsUniqueDatabaseSafeRuntimeNamesBeforeInsert() {
        PrivateProxy first = new PrivateProxy();
        PrivateProxy second = new PrivateProxy();

        PrivateProxyService.assignRuntimeNames(first, true);
        PrivateProxyService.assignRuntimeNames(second, true);

        assertTrue(first.getServiceName().startsWith("private-proxy-"));
        assertTrue(first.getAdmissionName().startsWith("private-proxy-admission-"));
        assertFalse(first.getServiceName().isBlank());
        assertTrue(first.getServiceName().length() <= 120);
        assertTrue(first.getAdmissionName().length() <= 120);
        assertNotEquals(first.getServiceName(), second.getServiceName());
        assertNotEquals(first.getAdmissionName(), second.getAdmissionName());
    }

    @Test
    void omitsAdmissionNameWhenNoSourceAllowlistExists() {
        PrivateProxy proxy = new PrivateProxy();

        PrivateProxyService.assignRuntimeNames(proxy, false);

        assertFalse(proxy.getServiceName().isBlank());
        assertNull(proxy.getAdmissionName());
    }
}
