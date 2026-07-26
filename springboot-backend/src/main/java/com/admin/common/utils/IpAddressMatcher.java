package com.admin.common.utils;

import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;

public final class IpAddressMatcher {

    private IpAddressMatcher() { }

    public static boolean isAllowed(String sourceIp, String rules) {
        if (StringUtils.isBlank(sourceIp) || StringUtils.isBlank(rules)) return false;
        for (String rule : rules.split("[,\\s]+")) {
            if (StringUtils.isBlank(rule)) continue;
            try {
                if (matches(sourceIp, rule.trim())) return true;
            } catch (Exception ignored) {
                // Ignore only the invalid entry; valid entries must continue to work.
            }
        }
        return false;
    }

    static boolean matches(String sourceIp, String rule) throws Exception {
        String addressValue = sourceIp;
        if (addressValue.startsWith("[") && addressValue.contains("]")) {
            addressValue = addressValue.substring(1, addressValue.indexOf(']'));
        }
        int zoneIndex = addressValue.indexOf('%');
        if (zoneIndex > 0) addressValue = addressValue.substring(0, zoneIndex);
        if (!isLiteralAddress(addressValue)) return false;
        if (!rule.contains("/")) {
            if (!isLiteralAddress(rule)) return false;
            return InetAddress.getByName(addressValue).equals(InetAddress.getByName(rule));
        }

        String[] parts = rule.split("/", 2);
        if (!isLiteralAddress(parts[0])) return false;
        byte[] address = InetAddress.getByName(addressValue).getAddress();
        byte[] network = InetAddress.getByName(parts[0]).getAddress();
        if (address.length != network.length) return false;
        int prefix = Integer.parseInt(parts[1]);
        if (prefix < 0 || prefix > address.length * 8) return false;
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (address[index] != network[index]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    private static boolean isLiteralAddress(String value) {
        return StringUtils.isNotBlank(value) && value.matches("[0-9a-fA-F:.]+");
    }
}
