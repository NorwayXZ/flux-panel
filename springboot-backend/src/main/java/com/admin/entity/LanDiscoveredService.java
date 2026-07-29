package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class LanDiscoveredService implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long connectorId;
    private Integer userId;
    private String host;
    private Integer port;
    private String serviceType;
    private String serviceName;
    private String product;
    private String title;
    private String confidence;
    private Integer sensitive;
    private Long firstSeenAt;
    private Long lastSeenAt;
    private Long createdTime;
    private Long updatedTime;
}
