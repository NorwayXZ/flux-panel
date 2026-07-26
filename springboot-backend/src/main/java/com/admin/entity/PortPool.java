package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class PortPool implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String name;
    private Long nodeId;
    private String bindIp;
    private String publicHost;
    private Integer startPort;
    private Integer endPort;
    private Integer controlPort;
    private String authUsername;
    private String authPassword;
    private Integer defaultLeaseHours;
    private Integer maxLeaseHours;
    private Integer cooldownSeconds;
    private Integer status;
    private Long createdTime;
    private Long updatedTime;

    @TableField(exist = false)
    private String nodeName;
    @TableField(exist = false)
    private Integer totalPorts;
    @TableField(exist = false)
    private Integer usedPorts;
    @TableField(exist = false)
    private Integer availablePorts;
    @TableField(exist = false)
    private Integer sharedPorts;
    @TableField(exist = false)
    private Long grantId;
    @TableField(exist = false)
    private Integer grantStartPort;
    @TableField(exist = false)
    private Integer grantEndPort;
    @TableField(exist = false)
    private Integer grantTotalPorts;
    @TableField(exist = false)
    private Integer grantUsedPorts;
    @TableField(exist = false)
    private String accessType;
}
