package com.admin.entity;

import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class Forward extends BaseEntity{

    private static final long serialVersionUID = 1L;

    private Integer userId;

    private String userName;

    private String name;

    private Integer tunnelId;

    private Integer inPort;

    private Integer outPort;

    /**
     * 隧道转发每一跳的监听端口，按 tunnel.nodePath[1..] 顺序逗号分隔。
     */
    private String hopPorts;

    private String remoteAddr;

    private String interfaceName;

    private String strategy;

    private Long inFlow;

    private Long outFlow;

    private Integer inx;

}
