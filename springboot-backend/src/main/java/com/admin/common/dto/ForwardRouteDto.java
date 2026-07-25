package com.admin.common.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ForwardRouteDto {

    private Integer tunnelId;

    private String tunnelName;

    private Integer priority;

    private Integer outPort;

    private String hopPorts;

    private String status = "unknown";

    private Double latency;

    private Double packetLoss;

    private Integer failCount = 0;

    private Integer successCount = 0;

    private Long healthySince;

    private Long lastFailureTime;

    private Long lastSuccessTime;

    private Long lastCheckTime;

    private String message;

    private List<String> healthyTargets = new ArrayList<>();
}
