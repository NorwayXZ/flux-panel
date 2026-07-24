package com.admin.service;

import com.admin.common.lang.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemUpdateServiceTests {

    @TempDir
    Path stateDirectory;

    @Test
    void reportsUnsupportedWhenHostServiceMarkerIsMissing() {
        SystemUpdateService service = new SystemUpdateService(stateDirectory.toString());

        Map<String, Object> status = service.getStatus();

        assertFalse((Boolean) status.get("supported"));
        assertEquals("idle", status.get("state"));
        assertEquals(503, service.triggerUpdate().getCode());
    }

    @Test
    void queuesOnlyOneUpdateRequest() throws Exception {
        Files.createFile(stateDirectory.resolve("enabled"));
        Files.writeString(
                stateDirectory.resolve("status.properties"),
                "state=idle\nmessage=Ready\nstartedAt=0\nfinishedAt=0\n",
                StandardCharsets.UTF_8
        );
        SystemUpdateService service = new SystemUpdateService(stateDirectory.toString());

        R firstResponse = service.triggerUpdate();
        R secondResponse = service.triggerUpdate();

        assertEquals(0, firstResponse.getCode());
        assertTrue(Files.isRegularFile(stateDirectory.resolve("update.request")));
        assertEquals(409, secondResponse.getCode());
        assertEquals("queued", service.getStatus().get("state"));
    }
}
