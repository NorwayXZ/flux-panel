package com.admin.common.dto;

import lombok.Data;

@Data
public class ForwardTargetHealthDto {

    private String address;

    private String status;

    private Double latency;

    private Double packetLoss;

    private Integer failCount;

    private Long lastCheckTime;

    private String message;
}
