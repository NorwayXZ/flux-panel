package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class AwsAccessAccountSaveDto {
    private Long id;

    @NotBlank(message = "请输入配置名称")
    @Size(max = 100, message = "配置名称不能超过100个字符")
    private String name;

    @NotBlank(message = "请输入 AWS Access Key ID")
    @Size(max = 128, message = "Access Key ID 不能超过128个字符")
    private String accessKeyId;

    @Size(max = 2048, message = "Secret Access Key 不能超过2048个字符")
    private String secretAccessKey;

    @Size(max = 64, message = "默认区域不能超过64个字符")
    private String defaultRegion;

    private Boolean enabled = true;
}
