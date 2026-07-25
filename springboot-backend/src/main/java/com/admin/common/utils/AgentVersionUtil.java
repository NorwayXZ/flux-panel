package com.admin.common.utils;

public final class AgentVersionUtil {
    private AgentVersionUtil() {
    }

    public static boolean isAtLeast(String actual, String required) {
        if (actual == null || actual.isBlank() || required == null || required.isBlank()) return false;
        String[] left = numericVersion(actual).split("\\.");
        String[] right = numericVersion(required).split("\\.");
        int length = Math.max(left.length, right.length);
        try {
            for (int i = 0; i < length; i++) {
                int leftPart = i < left.length ? Integer.parseInt(left[i]) : 0;
                int rightPart = i < right.length ? Integer.parseInt(right[i]) : 0;
                if (leftPart != rightPart) return leftPart > rightPart;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String numericVersion(String version) {
        return version.trim().replaceFirst("^[vV]", "").split("[-+]", 2)[0];
    }
}
