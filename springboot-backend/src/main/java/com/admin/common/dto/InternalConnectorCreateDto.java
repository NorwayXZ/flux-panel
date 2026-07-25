package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class InternalConnectorCreateDto {
    @NotBlank(message = "接入端名称不能为空")
    @Size(max = 80, message = "接入端名称不能超过80个字符")
    private String name;
    private String allowedCidrs;
}
