package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class DomainRouteBackendUpdateDto {
    @NotNull(message = "域名入口不能为空")
    private Long id;

    @Size(max = 128, message = "后端监听地址不能超过128个字符")
    private String backendHost;

    @Min(value = 1, message = "后端端口不能小于1")
    @Max(value = 65535, message = "后端端口不能大于65535")
    private Integer backendPort;

    private String backendScheme;

    @Size(max = 255, message = "后端根路径不能超过255个字符")
    private String backendPath;
}
