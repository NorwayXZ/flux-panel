package com.admin.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SniRouteTargetDto {
    private Long routeId;
    private String domain;
    private String targetAddress;
}
