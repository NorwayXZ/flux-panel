package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class LanDiscoveryScanDto {
    @NotNull(message = "家庭设备不能为空")
    private Long connectorId;
    private String cidr;
}
