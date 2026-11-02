package com.admin.common.utils;

/** Removes copy/paste padding without joining separate addresses or changing credentials. */
public final class AddressTextUtil {
    private AddressTextUtil() { }

    public static String trim(String value) {
        if (value == null) return null;
        int start = 0, end = value.length();
        while (start < end && padding(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && padding(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }

    private static boolean padding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT || Character.isISOControl(codePoint);
    }

    public static String host(String value) {
        String host = trim(value);
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("连接地址为空，请检查节点服务器地址");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || host.codePoints().anyMatch(AddressTextUtil::padding)
                || host.chars().anyMatch(c -> "/\\@?#,[]".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("连接地址包含空白或无效字符，请检查节点服务器地址或代理连接地址");
        }
        return host;
    }
}
