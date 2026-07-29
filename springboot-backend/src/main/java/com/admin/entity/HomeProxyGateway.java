package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class HomeProxyGateway implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long routeId;
    private Integer sequenceNo;
    private Long tunnelId;
    private Long nodeId;
    private Long poolId;
    private Long grantId;
    private Long leaseId;
    private Integer gatewayPort;
    private String gatewayName;
    private String gatewayType;
    private String runtimeName;
    private String authUsername;
    private String authPassword;
    private Long createdTime;
}
