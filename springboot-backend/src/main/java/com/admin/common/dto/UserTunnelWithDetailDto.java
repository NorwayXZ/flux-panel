package com.admin.common.dto;

import lombok.Data;

/**
 * <p>
 * 用户隧道权限及隧道详细信息DTO
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
public class UserTunnelWithDetailDto {
    
    /**
     * 用户隧道权限记录ID
     */
    private Integer id;
    
    /**
     * 用户ID
     */
    private Integer userId;
    
    /**
     * 隧道ID
     */
    private Integer tunnelId;
    
    /**
     * 流量限制
     */
    private Long flow;

    private Integer flowUnlimited;
    
    /**
     * 转发数量限制
     */
    private Integer num;

    private Integer forwardUnlimited;
    
    /**
     * 流量重置时间（时间戳）
     */
    private Long flowResetTime;
    
    /**
     * 到期时间（时间戳）
     */
    private Long expTime;
    
    /**
     * 限速规则ID
     */
    private Integer speedId;
    
    /**
     * 限速规则名称
     */
    private String speedLimitName;
    
    /**
     * 限速值
     */
    private Integer speed;
    
    /**
     * 隧道名称
     */
    private String tunnelName;
    
    /**
     * 隧道流量计算类型（1-单向，2-双向）
     */
    private Integer tunnelFlow;
    
    /**
     * 入站流量（字节）
     */
    private Long inFlow;
    
    /**
     * 出站流量（字节）
     */
    private Long outFlow;

    private Integer status;

}
