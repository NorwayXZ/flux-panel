package com.admin.common.dto;


import lombok.Data;

import java.util.List;

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

    // 累计失败连接数（新版 Agent）
    private Long e;

    // Agent 采样时间，毫秒时间戳
    private Long a;

    // 最近来源地址与域名的有界摘要
    private List<TelemetrySample> s;
    private List<TelemetrySample> h;

    @Data
    public static class TelemetrySample {
        private String v;
        private String k;
        private Long c;
        private Long l;
    }
}
