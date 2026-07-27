package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class PrivateProxyCreateDto {
    @NotBlank @Size(max = 100)
    private String name;
    @NotNull
    private Long nodeId;
    @NotBlank @Pattern(regexp = "socks5|http|shadowsocks|vless_reality", message = "不支持的代理类型")
    private String proxyType;
    @Size(max = 128)
    private String bindIp;
    @NotNull @Min(1) @Max(65535)
    private Integer listenPort;
    @Size(max = 64)
    private String authUsername;
    @Size(max = 128)
    private String authPassword;
    @Pattern(regexp = "|aes-128-gcm|aes-256-gcm|chacha20-ietf-poly1305", message = "不支持的 Shadowsocks 加密方式")
    private String cipher;
    @Size(max = 253)
    private String realityServerName;
    @Size(max = 1000)
    private String allowedCidrs;
    @Min(1) @Max(876000)
    private Integer leaseHours;
    @NotNull
    private Boolean permanent;
}
