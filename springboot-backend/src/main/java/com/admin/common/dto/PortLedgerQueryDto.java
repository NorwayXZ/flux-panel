package com.admin.common.dto;

import lombok.Data;

@Data
public class PortLedgerQueryDto {
    private Long nodeId;
    private Integer port;
    private String type;
    private String keyword;
}
