package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

@Data
public class AuthorizedEntryGrant {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Integer userId;
    private String name;
    private String accessHost;
    private Integer maxPorts;
    private Long flowLimitBytes;
    private String flowDirection;
    private Integer flowResetDay;
    private Long usedBytes;
    private Long lastResetAt;
    private Long expiresAt;
    private String state;
    private String lastError;
    private Long createdTime;
    private Long updatedTime;
}
