package com.admin.common.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class PrivateNetworkSaveDto {
    private Long id;

    @NotBlank(message = "内网组名称不能为空")
    private String name;

    @NotBlank(message = "内网类型不能为空")
    private String networkType;

    private String cidr;

    @Valid
    @NotEmpty(message = "至少选择两台服务器")
    private List<Member> members;

    @Data
    public static class Member {
        @NotNull(message = "节点不能为空")
        private Long nodeId;

        @NotBlank(message = "内网地址不能为空")
        private String privateAddress;

        private String interfaceName;

        @Min(value = 576, message = "MTU 不能小于 576")
        @Max(value = 9000, message = "MTU 不能大于 9000")
        private Integer mtu = 1500;
    }
}
