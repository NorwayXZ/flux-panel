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

    /** 第一项为主入口，其余按顺序作为备用入口。 */
    @NotEmpty(message = "请至少选择两个入口转发")
    private List<Long> memberForwardIds;
}
