package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class HomeProxyRouteCreateDto {
    @NotBlank(message = "家庭代理名称不能为空")
    @Size(max = 100, message = "家庭代理名称不能超过100个字符")
    private String name;
    @NotNull(message = "请选择家庭接入端")
    private Long connectorId;
    private String accessMode;
    private Long ingressPoolId;
    private Long ingressGrantId;
    @NotNull(message = "请选择家庭出口 VPS 端口池")
    private Long egressPoolId;
    private Long egressGrantId;
    private Integer directPort;
    private Boolean authEnabled;
    @Size(max = 64, message = "代理用户名不能超过64个字符")
    private String authUsername;
    @Size(max = 128, message = "代理密码不能超过128个字符")
    private String authPassword;
}
