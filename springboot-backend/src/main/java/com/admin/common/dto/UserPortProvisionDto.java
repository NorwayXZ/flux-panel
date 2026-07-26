package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class UserPortProvisionDto {
    private Long id;
    @NotNull(message = "端口池不能为空")
    private Long poolId;
    @NotNull @Min(1) @Max(65535)
    private Integer startPort;
    @NotNull @Min(1) @Max(65535)
    private Integer endPort;
}
