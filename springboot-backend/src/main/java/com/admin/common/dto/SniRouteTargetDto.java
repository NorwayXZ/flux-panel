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
    private Long backendId;
    private Integer weight = 100;
    private String selectionStrategy = "round";
    private String sessionAffinity = "none";

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

    public SniRouteTargetDto(Long routeId, Long backendId, String domain, String pathPrefix, String targetAddress,
                             String backendScheme, String backendPath, Integer weight,
                             String selectionStrategy, String sessionAffinity) {
        this(routeId, domain, pathPrefix, targetAddress, backendScheme, backendPath);
        this.backendId = backendId;
        this.weight = weight == null ? 100 : weight;
        this.selectionStrategy = selectionStrategy == null ? "round" : selectionStrategy;
        this.sessionAffinity = sessionAffinity == null ? "none" : sessionAffinity;
    }
}
