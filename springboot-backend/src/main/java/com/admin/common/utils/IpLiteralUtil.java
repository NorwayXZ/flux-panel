package com.admin.common.utils;

import java.net.Inet6Address;
import java.net.InetAddress;

public final class IpLiteralUtil {
    private IpLiteralUtil() {
    }

    public static String normalize(String value) {
        String address = value == null ? "" : value.trim();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("IP address is empty");
        }
        if (address.indexOf(':') >= 0) {
            return normalizeIpv6(address);
        }
        return normalizeIpv4(address);
    }

    public static boolean isLiteral(String value) {
        try {
            normalize(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address");
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid IPv4 address", e);
            }
            if (octet > 255) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            if (index > 0) normalized.append('.');
            normalized.append(octet);
        }
        return normalized.toString();
    }

    private static String normalizeIpv6(String value) {
        // Scoped addresses are local to one host and cannot safely identify a remote tunnel peer.
        if (value.indexOf('%') >= 0 || !value.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
        try {
            InetAddress parsed = InetAddress.getByName(value);
            if (!(parsed instanceof Inet6Address)) {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
            return parsed.getHostAddress();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid IPv6 address", e);
        }
    }
}
