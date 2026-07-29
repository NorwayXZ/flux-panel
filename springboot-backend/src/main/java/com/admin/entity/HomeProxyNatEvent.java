package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

@Data
public class HomeProxyNatEvent implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long routeId;
    private Integer userId;
    private String eventType;
    private String accessPath;
    private String detail;
    private Long createdTime;
}
