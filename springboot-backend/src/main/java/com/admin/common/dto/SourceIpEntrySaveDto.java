package com.admin.common.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

/** Configuration for one public TCP listener that routes by the real client IP. */
@Data
public class SourceIpEntrySaveDto {
    private Long id;

    @NotBlank(message = "请输入来源 IP 分流名称")
    @Size(max = 100, message = "来源 IP 分流名称不能超过100个字符")
    private String name;

    @NotNull(message = "请选择统一入口节点")
    private Long ingressNodeId;

    /** Empty means all local addresses. An explicit address is useful on multi-IP servers. */
    @Size(max = 128, message = "监听地址不能超过128个字符")
    private String listenHost = "";

    @NotNull(message = "请输入监听端口")
    private Integer listenPort;

    @NotEmpty(message = "至少配置一个默认或运营商线路")
    @Valid
    private List<Route> routes;

    private Boolean enabled = true;

    @Data
    public static class Route {
        /** default, telecom, unicom, mobile, or custom */
        @NotBlank(message = "线路类型不能为空")
        private String carrier;

        @NotNull(message = "请选择后端入口转发")
        private Long backendForwardId;

        /** Required for custom; carrier routes use the cached carrier database when blank. */
        private String cidrs;

        private Boolean enabled = true;
    }
}
