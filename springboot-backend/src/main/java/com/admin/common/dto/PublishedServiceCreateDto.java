package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class PublishedServiceCreateDto {
    @NotBlank(message = "服务名称不能为空")
    private String name;
    @NotNull(message = "内网接入端不能为空")
    private Long connectorId;
    @NotNull(message = "端口池不能为空")
    private Long poolId;
    private Long grantId;
    @NotBlank(message = "内网目标地址不能为空")
    private String targetHost;
    @NotNull @Min(1) @Max(65535)
    private Integer targetPort;
    @Min(1) @Max(876000)
    private Integer leaseHours;
    private Boolean permanent;
    @Min(1) @Max(65535)
    private Integer requestedPort;
}
