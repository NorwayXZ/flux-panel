package com.admin.common.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ServerAssetSaveDto {
    private Long id;
    private Long nodeId;
    private String name;
    private String provider;
    private String region;
    private String cpuSpec;
    private Integer memoryMb;
    private Integer diskGb;
    private Integer bandwidthMbps;
    private String currency;
    private BigDecimal monthlyCost;
    private Long purchaseDate;
    private Long expiryDate;
    private Boolean autoRenew;
    private String ipv4;
    private String ipv6;
    private String asn;
    private String networkLine;
    private String trafficPlan;
    private String tags;
    private String notes;
    private Boolean reminderEnabled;
    private String reminderDays;
}
