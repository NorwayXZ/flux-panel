package com.admin.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import com.admin.common.dto.TunnelPathNodeDto;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 隧道实体类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Tunnel extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 隧道名称
     */
    private String name;

    private Integer ownerUserId;

    /**
     * 入口节点ID
     */
    private Long inNodeId;

    /**
     * 入口IP (兼容字段)
     */
    private String inIp;

    /**
     * 出口节点ID
     */
    private Long outNodeId;

    /**
     * 出口IP (兼容字段)
     */
    private String outIp;

    /**
     * 节点路径，逗号分隔。隧道转发示例：1,2,3,4
     */
    private String nodePath;

    /** Per-hop public/private/virtual address policy as JSON. */
    private String hopConfig;

    /**
     * 隧道类型（1-端口转发，2-隧道转发）
     */
    private Integer type;

    /**
     * 流量计算类型（1 单向计算上传。2 双向）
     */
    private int flow;

    /**
     * 协议类型
     */
    private String protocol;

    /**
     * 流量倍率
     */
    private BigDecimal trafficRatio;


    private String tcpListenAddr;

    private String udpListenAddr;

    private String interfaceName;

    @TableField(exist = false)
    private String ownerUserName;

    @TableField(exist = false)
    private Integer ownerRoleId;

    @TableField(exist = false)
    private String accessType;

    @TableField(exist = false)
    private Boolean editable;

    @TableField(exist = false)
    private Boolean deletable;

    @TableField(exist = false)
    private List<TunnelPathNodeDto> pathNodeDetails;

    @TableField(exist = false)
    private List<com.admin.common.dto.TunnelHopDetailDto> hopDetails;

    @TableField(exist = false)
    private Long quotaFlow;

    @TableField(exist = false)
    private Long quotaUsedFlow;

    @TableField(exist = false)
    private Boolean quotaFlowUnlimited;

    @TableField(exist = false)
    private Integer quotaForwardLimit;

    @TableField(exist = false)
    private Integer quotaForwardUsed;

    @TableField(exist = false)
    private Boolean quotaForwardUnlimited;

    @TableField(exist = false)
    private Boolean quotaAvailable;

    @TableField(exist = false)
    private String unavailableReason;
}
