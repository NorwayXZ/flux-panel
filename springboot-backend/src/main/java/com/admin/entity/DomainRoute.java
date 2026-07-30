package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class DomainRoute implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer userId;
    private String name;
    private String domain;
    private String pathPrefix;
    private Long publishedServiceId;
    private String backendType;
    private Long backendNodeId;
    private String backendHost;
    private Integer backendPort;
    private String backendScheme;
    private String backendPath;
    private String backendStrategy;
    private String sessionAffinity;
    private Long nodeId;
    private Integer listenPort;
    private String serviceName;
    private String ingressMode;
    private Long dnsZoneId;
    private String dnsRecordId;
    private Long certificateId;
    private String state;
    private String lastError;
    private Long createdTime;
    private Long updatedTime;
    private String healthState;
    private Integer healthStatusCode;
    private Long healthLatencyMs;
    private Long healthCheckedAt;
    private String healthError;

    @TableField(exist = false)
    private String ownerUserName;
    @TableField(exist = false)
    private Integer ownerRoleId;
    @TableField(exist = false)
    private String nodeName;
    @TableField(exist = false)
    private Boolean nodeOnline;
    @TableField(exist = false)
    private String publicHost;
    @TableField(exist = false)
    private String mappingPublicHost;
    @TableField(exist = false)
    private String mappingName;
    @TableField(exist = false)
    private String mappingState;
    @TableField(exist = false)
    private Integer mappingPublicPort;
    @TableField(exist = false)
    private Boolean connectorOnline;
    @TableField(exist = false)
    private String backendNodeName;
    @TableField(exist = false)
    private Boolean backendNodeOnline;
    @TableField(exist = false)
    private String certificateState;
    @TableField(exist = false)
    private Long certificateExpiresAt;
    @TableField(exist = false)
    private String certificateIssuer;
    @TableField(exist = false)
    private List<Map<String, Object>> backendMembers;
}
