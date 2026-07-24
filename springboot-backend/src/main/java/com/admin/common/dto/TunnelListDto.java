package com.admin.common.dto;

import lombok.Data;

@Data
public class TunnelListDto {

    private Integer id;

    private String name;
    
    /**
     * 入口IP
     */
    private String ip;

    /**
     * 节点路径，逗号分隔
     */
    private String nodePath;
    
    /**
     * 入口节点端口起始范围
     */
    private Integer inNodePortSta;
    
    /**
     * 入口节点端口结束范围
     */
    private Integer inNodePortEnd;

    /**
     * 隧道类型（1-端口转发，2-隧道转发）
     */
    private Integer type;
    
    /**
     * 协议类型
     */
    private String protocol;
}
