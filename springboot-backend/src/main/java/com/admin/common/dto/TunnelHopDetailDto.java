package com.admin.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class TunnelHopDetailDto {
    private Long fromNodeId;
    private String fromNodeName;
    private Long toNodeId;
    private String toNodeName;
    private String addressMode;
    private String addressModeName;
    private Long resourceGroupId;
    private String resourceGroupName;
    private String targetAddress;
    private String fallbackMode;
    private String fallbackAddress;
    private String verificationState;
    private Long verifiedAt;
    private List<String> candidates;
}
