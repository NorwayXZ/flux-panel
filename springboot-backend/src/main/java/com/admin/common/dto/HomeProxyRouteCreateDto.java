package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class HomeProxyRouteCreateDto {
    @NotBlank(message = "家庭代理名称不能为空")
    @Size(max = 100, message = "家庭代理名称不能超过100个字符")
    private String name;
    @javax.validation.constraints.NotNull(message = "请选择家庭接入端")
    private Long connectorId;
    private String accessMode;
    private Long ingressPoolId;
    private Long ingressGrantId;
    private Long egressPoolId;
    private Long egressGrantId;
    private Long egressNodeId;
    private String egressMode;
    private Long egressTunnelId;
    private String transportMode;
    @Size(max = 253, message = "REALITY 伪装域名不能超过253个字符")
    private String realityServerName;
    private Integer directPort;
    private Long dynamicDnsRuleId;
    private Boolean authEnabled;
    @Size(max = 64, message = "代理用户名不能超过64个字符")
    private String authUsername;
    @Size(max = 128, message = "代理密码不能超过128个字符")
    private String authPassword;
}
