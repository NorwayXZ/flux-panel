package com.admin.common.utils;

import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;

public final class ClientIpUtil {

    private static final String TRUSTED_PROXY_RANGES = "127.0.0.0/8 ::1/128 172.20.0.0/16";

    private ClientIpUtil() { }

    public static String resolve(HttpServletRequest request) {
        String remoteAddress = StringUtils.defaultIfBlank(request.getRemoteAddr(), "unknown").trim();
        if (!IpAddressMatcher.isAllowed(remoteAddress, TRUSTED_PROXY_RANGES)) return remoteAddress;
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.isBlank(realIp) ? remoteAddress : realIp.trim();
    }
}
