package com.admin.common.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.Map;
import java.util.regex.Pattern;

public final class SensitiveDataUtils {

    private static final String MASK = "******";
    private static final Pattern PLAIN_SECRET_PATTERN = Pattern.compile(
        "(?i)(secret|password|token|authorization)(\\s*[=:]\\s*)([^,\\s&}]+)"
    );

    private SensitiveDataUtils() {
    }

    public static String maskJsonText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        try {
            Object parsed = JSON.parse(text);
            Object masked = maskValue(parsed, null);
            return JSON.toJSONString(masked);
        } catch (Exception ignored) {
            return maskPlainText(text);
        }
    }

    private static Object maskValue(Object value, String key) {
        if (isSensitiveKey(key)) {
            return MASK;
        }

        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject masked = new JSONObject();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                masked.put(entry.getKey(), maskValue(entry.getValue(), entry.getKey()));
            }
            return masked;
        }

        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray masked = new JSONArray();
            for (Object item : source) {
                masked.add(maskValue(item, null));
            }
            return masked;
        }

        return value;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase();
        return normalized.contains("secret")
            || normalized.contains("password")
            || normalized.contains("token")
            || normalized.contains("ticket")
            || normalized.contains("authorization")
            || normalized.equals("rawdata");
    }

    private static String maskPlainText(String text) {
        return PLAIN_SECRET_PATTERN.matcher(text).replaceAll("$1$2" + MASK);
    }
}
