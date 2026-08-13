package com.admin.common.dto;

import lombok.Data;

/**
 * <p>
 * 转发信息及关联隧道信息DTO
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
public class ForwardWithTunnelDto {
    
    /**
     * 转发记录ID
     */
    private Long id;
    

    /**
     * 转发名称
     */
    private String name;
    

    /**
     * 入口端口
     */
    private Integer inPort;

    /**
     * 隧道转发每一跳端口
     */
    private String hopPorts;

    /**
     * 远程地址
     */
    private String remoteAddr;
    
    /**
     * 转发状态
     */
    private Integer status;
    
    /**
     * 创建时间
     */
    private Long createdTime;
    
    /**
     * 更新时间
     */
    private Long updatedTime;
    
    // 以下为隧道相关字段
    
    /**
     * 隧道名称
     */
    private String tunnelName;
    
    /**
     * 入口IP
     */
    private String inIp;

    /**
     * 出口IP
     */
    private String outIp;

    /**
     * 隧道节点路径，逗号分隔
     */
    private String nodePath;

    /**
     * 入口节点ID
     */
    private Long inNodeId;

    /**
     * 出口节点ID
     */
    private Long outNodeId;

    /**
     * 入口节点状态
     */
    private Integer inNodeStatus;

    /**
     * 出口节点状态
     */
    private Integer outNodeStatus;

    /**
     * 隧道关联节点是否有离线
     */
    private Boolean nodeOffline;

    /**
     * 隧道类型
     */
    private Integer type;

    /**
     * 隧道协议
     */
    private String protocol;

    private String userName;


    /**
     * 用户ID
     */
    private Integer userId;
    /**
     * 隧道ID
     */
    private Integer tunnelId;

    /**
     * 入站流量（字节）
     */
    private Long inFlow;
    
    /**
     * 出站流量（字节）
     */
    private Long outFlow;

    private String strategy;

    private String routeMode;

    private String routeBalanceStrategy;

    private String routeConfig;

    private Integer activeTunnelId;

    private String protocolMode;

    private String targetHealth;

    private Long lastHealthCheck;

    private Integer previousActiveTunnelId;

    private Long lastRouteSwitch;

    private String routeSwitchReason;

    private Integer routeSwitchCount;

    private Integer inx;

    private String interfaceName;

    private String managedType;

    private String managedName;
}
