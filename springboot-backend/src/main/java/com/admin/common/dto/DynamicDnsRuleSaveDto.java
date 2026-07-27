package com.admin.common.dto;

import lombok.Data;

@Data
public class DynamicDnsRuleSaveDto {
    private Long id;
    private String name;
    private Long nodeId;
    private String providerSource;
    private Long providerRefId;
    private String provider;
    private Long zoneRefId;
    private String zoneName;
    private String recordName;
    private String recordType;
    private Integer ttl;
    private Integer checkIntervalSeconds;
    private Boolean enabled;
}
