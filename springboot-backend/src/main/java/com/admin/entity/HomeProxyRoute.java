package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class HomeProxyRoute implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer userId;
    private String name;
    private Long connectorId;
    private String accessMode;
    private Long ingressPoolId;
    private Long egressPoolId;
    private Long egressNodeId;
    private String egressMode;
    private Long egressTunnelId;
    private String transportMode;
    private String realityServerName;
    private Long leaseId;
    private Integer publicPort;
    private Long egressLeaseId;
    private Integer egressGatewayPort;
    private String directIpv6;
    private String directIpv4;
    private Integer directPort;
    private Long ipv6CheckedAt;
    private Long ipCheckedAt;
    private Long dynamicDnsRuleId;
    private String publicDomain;
    private String proxyType;
    private Integer authEnabled;
    private String authUsername;
    private String authPassword;
    private String state;
    private String lastError;
    private Long createdTime;
    private Long updatedTime;

    @TableField(exist = false)
    private String ownerUserName;
    @TableField(exist = false)
    private String connectorName;
    @TableField(exist = false)
    private Boolean connectorOnline;
    @TableField(exist = false)
    private String ingressPoolName;
    @TableField(exist = false)
    private String egressPoolName;
    @TableField(exist = false)
    private String egressTunnelName;
    @TableField(exist = false)
    private String egressNodeName;
    @TableField(exist = false)
    private Boolean egressNodeOnline;
    @TableField(exist = false)
    private java.util.List<com.admin.common.dto.TunnelPathNodeDto> egressPathNodeDetails;
    @TableField(exist = false)
    private String publicHost;
}
