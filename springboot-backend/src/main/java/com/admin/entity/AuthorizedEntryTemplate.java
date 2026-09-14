package com.admin.entity;

import lombok.Data;

@Data
public class AuthorizedEntryTemplate extends BaseEntity {
    private String name;
    private Long sourceGroupId;
    private Integer startPort;
    private Integer endPort;
    private String protocolMode;
    private String blockedTargetCidrs;
}
