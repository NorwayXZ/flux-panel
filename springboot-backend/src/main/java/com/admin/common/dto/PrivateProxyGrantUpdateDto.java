package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class PrivateProxyGrantUpdateDto {
    @NotNull
    private Long id;

    @NotNull
    @Min(0)
    private Long flowLimit;

    @NotNull
    private Boolean flowUnlimited;

    @NotNull
    @Min(0)
    @Max(31)
    private Integer flowResetDay;

    @NotNull
    private Boolean permanent;

    private Long expiresAt;

    @Min(1)
    @Max(100000)
    private Integer speedLimitMbps;
}
