package com.admin.common.dto;

import lombok.Data;

@Data
public class TunnelHopConfigDto {
    private Long fromNodeId;
    private Long toNodeId;
    private String addressMode;
    private Long resourceGroupId;
    private String customAddress;
    private String fallbackMode;
}
