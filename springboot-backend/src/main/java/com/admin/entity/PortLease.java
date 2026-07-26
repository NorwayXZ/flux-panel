package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class PortLease implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long poolId;
    private Long grantId;
    private Long serviceId;
    private Integer userId;
    private Integer port;
    private String protocol;
    private String state;
    private Long expiresAt;
    private Long releaseAfter;
    private Long createdTime;
    private Long updatedTime;
}
