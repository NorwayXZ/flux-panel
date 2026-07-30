package com.admin.common.dto;

import lombok.Data;

@Data
public class SniRouteTargetDto {
    private Long routeId;
    private String domain;
    private String pathPrefix;
    private String targetAddress;
    private String backendScheme;
    private String backendPath;

    public SniRouteTargetDto(Long routeId, String domain, String pathPrefix, String targetAddress) {
        this(routeId, domain, pathPrefix, targetAddress, "http", "/");
    }

    public SniRouteTargetDto(Long routeId, String domain, String pathPrefix, String targetAddress,
                             String backendScheme, String backendPath) {
        this.routeId = routeId;
        this.domain = domain;
        this.pathPrefix = pathPrefix;
        this.targetAddress = targetAddress;
        this.backendScheme = backendScheme;
        this.backendPath = backendPath;
    }
}
