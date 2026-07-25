package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class PublishedService implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer userId;
    private String name;
    private Long connectorId;
    private Long poolId;
    private Long leaseId;
    private String targetHost;
    private Integer targetPort;
    private Integer publicPort;
    private String protocol;
    private String state;
    private Integer leaseHours;
    private Long expiresAt;
    private String serviceName;
    private String lastError;
    private Long createdTime;
    private Long updatedTime;

    @TableField(exist = false)
    private String connectorName;
    @TableField(exist = false)
    private Boolean connectorOnline;
    @TableField(exist = false)
    private String poolName;
    @TableField(exist = false)
    private String publicHost;
    @TableField(exist = false)
    private String ownerUserName;
}
