package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

@Data
public class AuthorizedEntryPort {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long grantId;
    private Integer userId;
    private Integer port;
    private String targetHost;
    private Integer targetPort;
    private String protocolMode;
    private String state;
    private Long inFlow;
    private Long outFlow;
    private Long chargedBytes;
    private String lastError;
    private Long createdTime;
    private Long updatedTime;
}
