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

    @NotBlank @Pattern(regexp = "socks5|http|vless_reality|vless_xhttp_tls", message = "入口协议不受支持")
    private String proxyType;

    private String bindIp;

    @NotNull @Min(1) @Max(65535)
    private Integer listenPort;

    @Size(max = 64)
    private String username;

    @Size(max = 128)
    private String password;

    @Size(max = 253)
    private String realityServerName;

    @Size(max = 255)
    private String xhttpPath;

    @Pattern(regexp = "auto|packet-up|stream-up", message = "XHTTP 模式不正确")
    private String xhttpMode;

    @Size(max = 32)
    private String xhttpPaddingBytes;

    @Size(max = 253)
    private String xhttpOriginDomain;

    @Size(max = 253)
    private String xhttpUploadDomain;

    @Size(max = 253)
    private String xhttpDownloadDomain;

    private Boolean autoProvisionCloudFront;

    private Long awsAccessAccountId;

    private Long dnsZoneId;
}
