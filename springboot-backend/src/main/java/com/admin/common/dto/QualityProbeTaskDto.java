package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class QualityProbeTaskDto {
    private Long id;
    @NotBlank @Size(max = 100) private String name;
    @NotNull private Long sourceNodeId;
    @Pattern(regexp = "custom|node") private String targetType = "custom";
    private Long targetNodeId;
    @NotBlank @Size(max = 253) private String targetHost;
    @NotNull @Min(1) @Max(65535) private Integer port;
    @NotBlank @Pattern(regexp = "tcp|tls|http|https") private String protocol;
    @Size(max = 512) private String path = "/";
    @Size(max = 253) private String serverName;
    @NotBlank @Pattern(regexp = "auto|ipv4|ipv6") private String ipFamily = "auto";
    @NotNull @Min(1) @Max(10) private Integer sampleCount = 5;
    @NotNull @Min(500) @Max(15000) private Integer timeoutMs = 5000;
    @NotNull @Min(5) @Max(1440) private Integer intervalMinutes = 15;
    @NotNull @Min(1) @Max(365) private Integer retentionDays = 30;
    private Boolean enabled = false;
}
