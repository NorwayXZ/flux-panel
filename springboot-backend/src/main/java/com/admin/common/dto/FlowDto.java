package com.admin.common.dto;


import lombok.Data;

@Data
public class FlowDto {
    // 转发id_类型
    private String n;

    // 上传流量
    private Long u;

    // 下载流量
    private Long d;

    // 累计连接数（新版 Agent）
    private Long t;

    // 当前连接数（新版 Agent）
    private Long c;
}
