package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class CrossEntryFailoverSaveDto {
    private Long id;

    @NotBlank(message = "请输入容灾组名称")
    @Size(max = 100, message = "容灾组名称不能超过100个字符")
    private String name;

    @NotBlank(message = "请输入业务域名")
    private String domain;

    private Long dnsZoneId;

    /** 兼容 2.15.1 及更早版本的容灾配置。 */
    private String zoneId;

    private String recordId;
    private String apiToken;
    private String recordType = "A";
    private Integer ttl = 60;
    private Integer probeIntervalMs = 2000;
    private Integer connectTimeoutMs = 1200;
    private Integer failureThreshold = 2;
    private Integer recoveryThreshold = 3;
    private Integer cooldownSeconds = 30;
    private Boolean autoFailback = false;
    private Boolean enabled = true;

    /** failover keeps one DNS answer; active_active publishes every healthy entry. */
    private String routingMode = "failover";

    /** Switches a still-reachable primary when latency/loss is persistently worse than baseline. */
    private Boolean qualityEnabled = false;
    private String qualityProbeSourceType = "panel";
    private Long qualityProbeSourceId;
    private Integer qualityProbeCount = 4;
    private Integer qualityDegradeThresholdMs = 100;
    private Integer qualityRecoverThresholdMs = 60;
    private Double qualityDegradeFactor = 3.0;
    private Double qualityRecoverFactor = 1.8;
    private Integer qualityDegradeSamples = 3;
    private Integer qualityRecoverSamples = 3;
    private Double qualityLossThresholdPercent = 30.0;
    private Boolean qualityFixedTargetEnabled = false;
    private Integer qualityFixedTargetMs = 20;
    private Boolean qualityFixedTargetStrict = true;
    private Boolean qualityFlapGuardEnabled = true;
    private Integer qualityFlapWindowSeconds = 900;
    private Integer qualityFlapThreshold = 3;
    private Integer qualityFlapSuppressSeconds = 1800;

    /** Mirrors memberForwardIds by position. DNS itself does not guarantee strict weighting. */
    private List<Integer> memberWeights;

    /** 第一项为主入口，其余按顺序作为备用入口。 */
    @NotEmpty(message = "请至少选择两个入口转发")
    private List<Long> memberForwardIds;
}
