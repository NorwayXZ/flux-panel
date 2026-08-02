package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class NftForwardSaveDto {
    private Long id;

    @NotBlank(message = "请输入规则名称")
    @Size(max = 100, message = "规则名称不能超过100个字符")
    private String name;

    @NotNull(message = "请选择执行节点")
    private Long nodeId;

    @Size(max = 45, message = "监听地址不能超过45个字符")
    private String listenAddress = "0.0.0.0";

    @NotNull(message = "请输入入口端口")
    @Min(value = 1, message = "入口端口必须在1到65535之间")
    @Max(value = 65535, message = "入口端口必须在1到65535之间")
    private Integer listenPort;

    @NotBlank(message = "请选择协议")
    private String protocol = "tcp";

    @NotBlank(message = "请输入目标 IPv4")
    @Size(max = 45, message = "目标地址不能超过45个字符")
    private String targetAddress;

    @NotNull(message = "请输入目标端口")
    @Min(value = 1, message = "目标端口必须在1到65535之间")
    @Max(value = 65535, message = "目标端口必须在1到65535之间")
    private Integer targetPort;

    @NotBlank(message = "请选择 NAT 模式")
    private String natMode = "masquerade";

    @Size(max = 4096, message = "来源白名单内容过长")
    private String sourceCidrs;

    private Boolean enabled = true;
}
