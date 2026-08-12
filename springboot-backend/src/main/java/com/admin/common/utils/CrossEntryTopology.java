package com.admin.common.utils;

import org.apache.commons.lang3.StringUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class CrossEntryTopology {
    private CrossEntryTopology() {
    }

    public static String signature(String address, String provider, String asn, String label) {
        return String.join("|", keys(address, provider, asn, label));
    }

    public static Set<String> keys(String address, String provider, String asn, String label) {
        Set<String> keys = new LinkedHashSet<>();
        String providerKey = providerKey(provider, label);
        if (StringUtils.isNotBlank(providerKey)) keys.add("provider:" + providerKey);
        String asnKey = asnKey(asn);
        if (StringUtils.isNotBlank(asnKey)) keys.add("asn:" + asnKey);
        String networkKey = networkKey(address);
        if (StringUtils.isNotBlank(networkKey)) keys.add(networkKey);
        return keys;
    }

    public static Set<String> keysFromSignatureOrAddress(String signature, String address) {
        Set<String> keys = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(signature)) {
            for (String part : signature.split("\\|")) {
                String value = StringUtils.trimToEmpty(part);
                if (StringUtils.isNotBlank(value)) keys.add(value);
            }
        }
        String networkKey = networkKey(address);
        if (StringUtils.isNotBlank(networkKey)) keys.add(networkKey);
        return keys;
    }

    public static boolean overlaps(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return false;
        for (String key : left) {
            if (right.contains(key)) return true;
        }
        return false;
    }

    static String networkKey(String address) {
        if (StringUtils.isBlank(address)) return "";
        try {
            InetAddress parsed = InetAddress.getByName(address.trim());
            byte[] bytes = parsed.getAddress();
            if (parsed instanceof Inet4Address && bytes.length == 4) {
                return "ipv4:" + unsigned(bytes[0]) + "." + unsigned(bytes[1]) + ".0.0/16";
            }
            if (parsed instanceof Inet6Address && bytes.length == 16) {
                return String.format(Locale.ROOT, "ipv6:%02x%02x:%02x%02x:%02x%02x::/48",
                        unsigned(bytes[0]), unsigned(bytes[1]), unsigned(bytes[2]), unsigned(bytes[3]),
                        unsigned(bytes[4]), unsigned(bytes[5]));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static String asnKey(String asn) {
        String value = StringUtils.lowerCase(StringUtils.trimToEmpty(asn), Locale.ROOT);
        if (StringUtils.isBlank(value)) return "";
        String digits = value.replaceAll("[^0-9]", "");
        return StringUtils.isBlank(digits) ? value.replaceAll("[^a-z0-9]+", "") : digits;
    }

    private static String providerKey(String provider, String label) {
        String explicit = normalizeProvider(provider);
        if (StringUtils.isNotBlank(explicit)) return explicit;
        return normalizeProvider(label);
    }

    private static String normalizeProvider(String value) {
        String text = StringUtils.lowerCase(StringUtils.trimToEmpty(value), Locale.ROOT);
        if (StringUtils.isBlank(text)) return "";
        if (text.contains("阿里") || text.contains("aliyun") || text.contains("alibaba")) return "aliyun";
        if (text.contains("腾讯") || text.contains("tencent")) return "tencent";
        if (text.contains("aws") || text.contains("amazon")) return "aws";
        if (text.contains("gcp") || text.contains("google")) return "gcp";
        if (text.contains("azure") || text.contains("microsoft")) return "azure";
        if (text.contains("oracle") || text.contains("甲骨")) return "oracle";
        if (text.contains("cloudflare")) return "cloudflare";
        if (text.contains("vultr")) return "vultr";
        if (text.contains("linode") || text.contains("akamai")) return "linode";
        if (text.contains("digitalocean")) return "digitalocean";
        if (text.contains("hetzner")) return "hetzner";
        if (text.contains("ovh")) return "ovh";
        return text.replaceAll("[^a-z0-9]+", "");
    }
}
