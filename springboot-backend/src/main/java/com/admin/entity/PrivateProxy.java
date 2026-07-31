package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

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
    private Integer grantedByUserId;
    private Long flowLimit;
    private Integer flowUnlimited;
    private Integer flowResetDay;
    private Long lastFlowResetAt;
    private Integer speedLimitMbps;
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
}
