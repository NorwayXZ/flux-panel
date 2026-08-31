package com.admin.service;

import com.admin.common.lang.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class SystemUpdateService {

    private static final int MAX_LOG_BYTES = 64 * 1024;
    private static final int MAX_LOG_LINES = 80;

    private final Path stateDirectory;

    public SystemUpdateService(@Value("${flux-panel.updater-dir:/var/lib/flux-panel-updater}") String stateDirectory) {
        this.stateDirectory = Path.of(stateDirectory).normalize();
    }

    public Map<String, Object> getStatus() {
        Properties properties = readProperties();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", isSupported());
        result.put("state", properties.getProperty("state", "idle"));
        result.put("message", properties.getProperty("message", "Ready"));
        result.put("startedAt", parseLong(properties.getProperty("startedAt")));
        result.put("finishedAt", parseLong(properties.getProperty("finishedAt")));
        result.put("logs", readLogTail());
        return result;
    }

    public synchronized R triggerUpdate() {
        return triggerUpdate(null);
    }

    public synchronized R triggerUpdate(String requestedVersion) {
        if (!isSupported()) {
            return R.err(503, "当前安装未启用在线更新服务，请先通过一键脚本完成升级或迁移");
        }

        String currentState = readProperties().getProperty("state", "idle");
        if ("queued".equals(currentState) || "running".equals(currentState)
                || Files.exists(stateDirectory.resolve("update.request"))) {
            return R.err(409, "已有更新任务正在执行");
        }

        try {
            String version = normalizeVersion(requestedVersion);
            writeStatus("queued", "Waiting for the host update service", 0, 0);
            Files.writeString(
                    stateDirectory.resolve("update.request"),
                    "version=" + version + "\nrequestedAt=" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            return R.ok(getStatus());
        } catch (IOException exception) {
            return R.err(500, "无法提交更新任务：" + exception.getMessage());
        }
    }

    private String normalizeVersion(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replaceFirst("^[vV]", "");
        if (!normalized.matches("[0-9]+\\.[0-9]+\\.[0-9]+([.-][0-9A-Za-z.-]+)?")) {
            throw new IllegalArgumentException("更新版本号格式不正确");
        }
        return normalized;
    }

    private boolean isSupported() {
        return Files.isRegularFile(stateDirectory.resolve("enabled"))
                && Files.isDirectory(stateDirectory)
                && Files.isWritable(stateDirectory);
    }

    private Properties readProperties() {
        Properties properties = new Properties();
        Path statusFile = stateDirectory.resolve("status.properties");
        if (!Files.isRegularFile(statusFile)) {
            return properties;
        }

        try (var reader = Files.newBufferedReader(statusFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            properties.setProperty("state", "unknown");
            properties.setProperty("message", "Unable to read update status");
        }
        return properties;
    }

    private void writeStatus(String state, String message, long startedAt, long finishedAt) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("state", state);
        properties.setProperty("message", message);
        properties.setProperty("startedAt", Long.toString(startedAt));
        properties.setProperty("finishedAt", Long.toString(finishedAt));

        Path temporary = Files.createTempFile(stateDirectory, "status.", ".tmp");
        try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }

        Path destination = stateDirectory.resolve("status.properties");
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<String> readLogTail() {
        Path logFile = stateDirectory.resolve("update.log");
        if (!Files.isRegularFile(logFile)) {
            return List.of();
        }

        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = file.length();
            int bytesToRead = (int) Math.min(length, MAX_LOG_BYTES);
            byte[] bytes = new byte[bytesToRead];
            file.seek(length - bytesToRead);
            file.readFully(bytes);

            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
            int firstLine = Math.max(0, lines.length - MAX_LOG_LINES);
            List<String> result = new ArrayList<>(lines.length - firstLine);
            for (int index = firstLine; index < lines.length; index++) {
                if (!lines[index].isBlank()) {
                    result.add(lines[index]);
                }
            }
            return result;
        } catch (IOException ignored) {
            return List.of("Unable to read the host update log");
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
