package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class DomainRoute implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer userId;
    private String name;
    private String domain;
    private Long publishedServiceId;
    private Long nodeId;
    private Integer listenPort;
    private String serviceName;
    private String state;
    private String lastError;
    private Long createdTime;
    private Long updatedTime;

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
    private String mappingName;
    @TableField(exist = false)
    private String mappingState;
    @TableField(exist = false)
    private Integer mappingPublicPort;
    @TableField(exist = false)
    private Boolean connectorOnline;
}
