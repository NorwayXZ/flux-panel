package com.admin.common.utils;

import org.apache.commons.lang3.StringUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Validates tenant targets so public ingress cannot be used to reach private infrastructure. */
public final class AuthorizedEntryTargetValidator {
    private AuthorizedEntryTargetValidator() {
    }

    public static String validatePublicLiteral(String rawHost, Integer port) {
        String host = stripBrackets(StringUtils.trimToEmpty(rawHost));
        if (host.isEmpty() || port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("落地必须填写有效的公网 IP 和端口");
        }
        if (!host.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("落地只允许数值公网 IP，不允许域名");
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address instanceof Inet4Address && !isPublicIpv4(address.getAddress())) {
                throw new IllegalArgumentException("落地 IP 不能是内网、保留地址或云元数据地址");
            }
            if (address instanceof Inet6Address && !isPublicIpv6(address.getAddress())) {
                throw new IllegalArgumentException("落地 IPv6 不能是本机、内网、链路本地或保留地址");
            }
            return address instanceof Inet6Address ? "[" + address.getHostAddress() + "]:" + port
                    : address.getHostAddress() + ":" + port;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("落地 IP 格式不正确");
        }
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int a = bytes[0] & 0xff;
        int b = bytes[1] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && (b == 0 || b == 168)) return false;
        if (a == 192 && b == 0 && (bytes[2] & 0xff) == 2) return false;
        if (a == 192 && b == 31 && (bytes[2] & 0xff) == 196) return false;
        if (a == 192 && b == 52 && (bytes[2] & 0xff) == 193) return false;
        if (a == 192 && b == 88 && (bytes[2] & 0xff) == 99) return false;
        if (a == 192 && b == 175 && (bytes[2] & 0xff) == 48) return false;
        if (a == 198 && (b == 18 || b == 19)) return false;
        if (a == 198 && b == 51 && (bytes[2] & 0xff) == 100) return false;
        return !(a == 203 && b == 0 && (bytes[2] & 0xff) == 113);
    }

    private static boolean isPublicIpv6(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0 || first == 0xff) return false;
        if (first == 0xfc || first == 0xfd) return false;
        return !(first == 0xfe && (second & 0xc0) == 0x80);
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
