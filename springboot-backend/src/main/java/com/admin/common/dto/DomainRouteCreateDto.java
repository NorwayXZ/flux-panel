package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class DomainRouteCreateDto {
    @NotBlank(message = "入口名称不能为空")
    @Size(max = 100, message = "入口名称不能超过100个字符")
    private String name;

    @NotBlank(message = "访问域名不能为空")
    @Size(max = 253, message = "域名不能超过253个字符")
    private String domain;

    @NotNull(message = "内网映射不能为空")
    private Long publishedServiceId;

    @NotNull(message = "监听端口不能为空")
    @Min(value = 1, message = "监听端口不能小于1")
    @Max(value = 65535, message = "监听端口不能大于65535")
    private Integer listenPort;

    private String ingressMode = "passthrough";

    private Long dnsZoneId;

    @Size(max = 255, message = "匹配路径不能超过255个字符")
    private String pathPrefix = "/";
}
