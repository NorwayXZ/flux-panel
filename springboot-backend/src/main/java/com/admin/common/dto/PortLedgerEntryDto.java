package com.admin.common.dto;

import lombok.Data;

@Data
public class PortLedgerEntryDto {
    private String key;
    private String type;
    private String status;
    private Long nodeId;
    private String nodeName;
    private String namespace;
    private String serverAddress;
    private Integer portStart;
    private Integer portEnd;
    private String protocol;
    private Integer ownerUserId;
    private String ownerUserName;
    private Long resourceId;
    private String resourceName;
    private String detail;
    private Long createdTime;
    private Long expiresAt;
}
