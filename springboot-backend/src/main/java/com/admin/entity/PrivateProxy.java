package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class PrivateProxy implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer userId;
    private String name;
    private Long nodeId;
    private String proxyType;
    private String bindIp;
    private Integer listenPort;
    private String authUsername;
    private String authPassword;
    private String allowedCidrs;
    private String state;
    private Long expiresAt;
    private String serviceName;
    private String admissionName;
    private String clientConfig;
    private String lastError;
    private Long inFlow;
    private Long outFlow;
    private Long runtimeInFlow;
    private Long runtimeOutFlow;
    private Integer grantedByUserId;
    private Long flowLimit;
    private Integer flowUnlimited;
    private Integer flowResetDay;
    private Long lastFlowResetAt;
    private Integer speedLimitMbps;
    private String exitType;
    private String exitCountry;
    private Integer exitRoutePort;
    private String exitSwitchToken;
    private String connectHost;
    private Long lastCheckAt;
    private Integer lastCheckOk;
    private String lastCheckIp;
    private Integer lastCheckLatency;
    private String lastCheckError;
    private Integer checkFailures;
    private Long lastActiveAt;
    private Long createdTime;
    private Long updatedTime;

    @TableField(exist = false) private String nodeName;
    @TableField(exist = false) private String publicHost;
    @TableField(exist = false) private String ownerUserName;
    @TableField(exist = false) private Boolean nodeOnline;
    @TableField(exist = false) private Boolean passwordConfigured;
    @TableField(exist = false) private Boolean granted;
    @TableField(exist = false) private Boolean available;
    @TableField(exist = false) private String unavailableReason;
    @TableField(exist = false) private Long remainingFlow;
    @TableField(exist = false) private Long remainingTime;
    @TableField(exist = false) private Boolean speedLimitSupported;
    @TableField(exist = false) private Map<String, Object> exit;
    /** 分享链接不校验证书时为 true（自签证书且该链接格式无法固定证书） */
    @TableField(exist = false) private Boolean linkSkipsCertCheck;
    @TableField(exist = false) private String transport;
}
