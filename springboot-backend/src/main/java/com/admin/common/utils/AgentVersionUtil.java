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

    /** Upgrade decisions include prerelease identifiers; feature gates keep numeric comparison. */
    public static boolean isReleaseAtLeast(String actual, String required) {
        if (!isAtLeast(actual, required)) return false;
        if (!isAtLeast(required, actual)) return true;
        String left = actual.trim().replaceFirst("^[vV]", "").split("\\+", 2)[0];
        String right = required.trim().replaceFirst("^[vV]", "").split("\\+", 2)[0];
        String[] a = left.split("-", 2), b = right.split("-", 2);
        if (a.length == 1) return true;
        if (b.length == 1) return false;
        String[] aa = a[1].split("\\."), bb = b[1].split("\\.");
        for (int i = 0; i < Math.min(aa.length, bb.length); i++) {
            boolean an = aa[i].matches("[0-9]+"), bn = bb[i].matches("[0-9]+");
            int comparison;
            if (an && bn) comparison = new java.math.BigInteger(aa[i]).compareTo(new java.math.BigInteger(bb[i]));
            else if (an != bn) comparison = an ? -1 : 1;
            else comparison = aa[i].compareTo(bb[i]);
            if (comparison != 0) return comparison > 0;
        }
        return aa.length >= bb.length;
    }
}
