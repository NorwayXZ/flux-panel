package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class InternalConnector implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer userId;
    private String name;
    private String secret;
    private String allowedCidrs;
    private String platform;
    private String version;
    private String remoteIp;
    private Long lastSeen;
    private Integer discoveryEnabled;
    private String discoveryStatus;
    private Long discoveryLastScanAt;
    private String discoveryLastCidr;
    private String discoveryLastError;
    private Integer status;
    private Long createdTime;
    private Long updatedTime;

    @TableField(exist = false)
    private Boolean online;
    @TableField(exist = false)
    private String ownerUserName;
    @TableField(exist = false)
    private Integer discoveredServiceCount;
}
