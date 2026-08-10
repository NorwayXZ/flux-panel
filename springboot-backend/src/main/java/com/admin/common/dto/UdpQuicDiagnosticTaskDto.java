package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class UdpQuicDiagnosticTaskDto {
    private Long id;
    @NotBlank @Size(max = 100) private String name;
    @NotNull private Long sourceNodeId;
    @Pattern(regexp = "node|custom") private String targetType = "node";
    private Long targetNodeId;
    @Size(max = 253) private String targetHost;
    @NotNull @Min(1) @Max(65535) private Integer port = 443;
    @NotBlank @Pattern(regexp = "udp_echo|quic") private String mode = "udp_echo";
    @Size(max = 253) private String serverName;
    @NotBlank @Pattern(regexp = "auto|ipv4|ipv6") private String ipFamily = "auto";
    @NotNull @Min(1) @Max(20) private Integer sampleCount = 5;
    @NotNull @Min(300) @Max(20000) private Integer timeoutMs = 3000;
    @NotNull @Min(64) @Max(1400) private Integer packetSize = 1200;
    @NotNull @Min(0) @Max(60) private Integer idleTimeoutSeconds = 15;
    @Size(max = 100) private String alpn = "h3";
    private Boolean verifyCertificate = false;
    @NotNull @Min(1) @Max(365) private Integer retentionDays = 30;
}
