package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class NetworkRouteApplicationCreateDto {
    @NotBlank @Size(max = 100)
    private String name;

    private Long tunnelId;

    private java.util.List<Long> nodePath;

    private java.util.List<TunnelHopConfigDto> hopConfigs;

    private String tunnelProtocol;

    @NotBlank @Pattern(regexp = "socks5|http", message = "入口协议只允许 SOCKS5 或 HTTP")
    private String proxyType;

    private String bindIp;

    @NotNull @Min(1) @Max(65535)
    private Integer listenPort;

    @NotBlank @Size(min = 3, max = 64)
    private String username;

    @NotBlank @Size(min = 8, max = 128)
    private String password;
}
