package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class PortPoolCreateDto {
    @NotBlank(message = "端口池名称不能为空")
    private String name;
    @NotNull(message = "公网节点不能为空")
    private Long nodeId;
    private String bindIp;
    @NotBlank(message = "公网连接地址不能为空")
    private String publicHost;
    @NotNull @Min(1) @Max(65535)
    private Integer startPort;
    @NotNull @Min(1) @Max(65535)
    private Integer endPort;
    @NotNull @Min(1) @Max(65535)
    private Integer controlPort;
    @Min(0) @Max(86400)
    private Integer cooldownSeconds;
}
