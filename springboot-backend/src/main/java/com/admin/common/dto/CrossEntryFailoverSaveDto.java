package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
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
    private Integer qualityP95ThresholdMs = 100;
    private Integer qualityJitterThresholdMs = 50;
    private Boolean qualityFixedTargetEnabled = false;
    private Integer qualityFixedTargetMs = 20;
    /** false=达标优先，没达标就选最优；true=只切到达标备用入口。 */
    private Boolean qualityFixedTargetStrict = false;
    private Boolean qualityFlapGuardEnabled = true;
    private Integer qualityFlapWindowSeconds = 900;
    private Integer qualityFlapThreshold = 3;
    private Integer qualityFlapSuppressSeconds = 1800;
    private Boolean qualityPenaltyEnabled = true;
    private Integer qualityPenaltyResetSeconds = 86400;
    private Integer qualityPenaltyObserveSeconds = 900;
    private Boolean smartSelectionEnabled = true;
    /** Prefer the lowest measured TCP connect latency among healthy entries. */
    private Boolean tcpLatencySelectionEnabled = false;
    private Integer tcpLatencySwitchThresholdMs = 5;
    /** Keep the primary when it is no more than this many milliseconds slower than the fastest entry. */
    private Integer tcpPrimaryPreferenceToleranceMs = 10;
    private Boolean degradedFallbackEnabled = true;
    private Boolean sameFaultAvoidanceEnabled = true;
    private Boolean topologyAvoidanceEnabled = true;
    private Integer minResidencySeconds = 300;
    private Integer failbackGainMs = 5;
    private Double failbackGainPercent = 15.0;
    private Boolean preheatEnabled = true;
    private Integer preheatBackupCount = 3;
    private Boolean preheatStrictIsolation = true;
    private Boolean postSwitchVerifyEnabled = true;
    private Integer postSwitchRejectSuppressSeconds = 600;
    private Boolean dnsVerifyEnabled = true;
    private String manualControlMode = "auto";
    private Long lockedMemberId;
    private Long manualLockUntil;

    /** Mirrors memberForwardIds by position. DNS itself does not guarantee strict weighting. */
    private List<Integer> memberWeights;

    /**
     * existing_forward 引用已经创建的转发；managed_forward 由面板自动创建到固定落地的转发。
     * 旧请求不填写时继续使用 existing_forward。
     */
    private String creationMode = "existing_forward";

    /** 托管落地模式下的入口节点顺序，第一项为主入口。 */
    private List<Long> managedEntryNodeIds;

    /** 托管落地模式下的固定目标，例如 203.0.113.10:443。 */
    private String managedTargetAddress;

    /** custom 使用 managedPublicPort；auto 在范围内寻找所有入口都空闲的公共端口。 */
    private String managedPortMode = "auto";
    private Integer managedPublicPort;
    private Integer managedPortRangeStart = 10000;
    private Integer managedPortRangeEnd = 60000;
    private String managedProtocolMode = "tcp";

    /** Beijing-time preference windows. Empty keeps the existing failover behaviour. */
    private List<CrossEntryFailoverScheduleDto> schedules = new java.util.ArrayList<>();

    /** 第一项为主入口，其余按顺序作为备用入口。 */
    private List<Long> memberForwardIds;
}
