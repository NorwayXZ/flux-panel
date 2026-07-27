package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class DnsProviderAccountSaveDto {
    private Long id;

    @NotBlank(message = "请输入配置名称")
    @Size(max = 100, message = "配置名称不能超过100个字符")
    private String name;

    private String apiToken;
    private Boolean enabled = true;
}
