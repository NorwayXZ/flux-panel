package com.admin.common.dto;

import lombok.Data;

@Data
public class UserNodeProvisionDto {
    private Integer nodeId;
    private Long flow;
    private Boolean flowUnlimited;
    private Integer num;
    private Boolean forwardUnlimited;
    private Long flowResetTime;
    private Long expTime;
    private Integer status;
}
