package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class PortPoolGrant implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long poolId;
    private Integer userId;
    private Integer startPort;
    private Integer endPort;
    private Integer status;
    private Long createdTime;
    private Long updatedTime;

    @TableField(exist = false)
    private String poolName;
    @TableField(exist = false)
    private Long nodeId;
    @TableField(exist = false)
    private String nodeName;
    @TableField(exist = false)
    private String publicHost;
    @TableField(exist = false)
    private String ownerUserName;
    @TableField(exist = false)
    private Integer totalPorts;
    @TableField(exist = false)
    private Integer usedPorts;
    @TableField(exist = false)
    private Integer availablePorts;
}
