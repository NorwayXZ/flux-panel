package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizedEntryTargetValidatorTests {
    @Test
    void acceptsPublicIpLiterals() {
        assertEquals("8.8.8.8:443", AuthorizedEntryTargetValidator.validatePublicLiteral("8.8.8.8", 443));
        assertTrue(AuthorizedEntryTargetValidator.validatePublicLiteral("2606:4700:4700::1111", 443).endsWith("]:443"));
    }

    @Test
    void rejectsPrivateAndMetadataTargets() {
        assertThrows(IllegalArgumentException.class, () -> AuthorizedEntryTargetValidator.validatePublicLiteral("127.0.0.1", 80));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedEntryTargetValidator.validatePublicLiteral("10.0.0.1", 80));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedEntryTargetValidator.validatePublicLiteral("169.254.169.254", 80));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedEntryTargetValidator.validatePublicLiteral("192.0.2.10", 80));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedEntryTargetValidator.validatePublicLiteral("198.51.100.10", 80));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedEntryTargetValidator.validatePublicLiteral("203.0.113.10", 80));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedEntryTargetValidator.validatePublicLiteral("fc00::1", 80));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedEntryTargetValidator.validatePublicLiteral("example.com", 80));
    }
}
