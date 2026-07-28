package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class SmartEntrySaveDto {
    private Long id;

    @NotBlank(message = "请输入三网优化名称")
    @Size(max = 100, message = "三网优化名称不能超过100个字符")
    private String name;

    private Long providerRefId;

    @NotBlank(message = "请输入主域名")
    private String zoneName;

    @NotBlank(message = "请输入业务域名")
    private String domain;

    private String recordType = "A";
    private Integer ttl = 60;
    private Integer probeIntervalMs = 5000;
    private Integer connectTimeoutMs = 1500;
    private Integer failureThreshold = 2;
    private Integer recoveryThreshold = 3;
    private Boolean enabled = true;

    @NotEmpty(message = "请配置默认入口和运营商入口")
    private List<Route> routes;

    @Data
    public static class Route {
        private String carrier;
        private Long forwardId;
    }
}
