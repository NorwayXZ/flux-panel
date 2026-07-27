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
    @NotBlank @Pattern(regexp = "socks5|http", message = "代理类型仅支持 SOCKS5 或 HTTP")
    private String proxyType;
    @Size(max = 128)
    private String bindIp;
    @NotNull @Min(1) @Max(65535)
    private Integer listenPort;
    @NotBlank @Size(min = 3, max = 64)
    private String authUsername;
    @NotBlank @Size(min = 8, max = 128)
    private String authPassword;
    @Size(max = 1000)
    private String allowedCidrs;
    @Min(1) @Max(876000)
    private Integer leaseHours;
    @NotNull
    private Boolean permanent;
}
