package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Min;
import java.util.List;

@Data
public class UserDto {

    @NotBlank(message = "用户名不能为空")
    private String user;

    @NotBlank(message = "密码不能为空")
    private String pwd;

    @Min(value = 0, message = "流量不能小于0")
    private Long flow;

    private Boolean flowUnlimited;

    @Min(value = 0, message = "转发数量不能小于0")
    private Integer num;

    private Boolean forwardUnlimited;

    private Long expTime;

    private Long flowResetTime;

    private Integer status;

    private List<UserTunnelProvisionDto> tunnelPermissions;

    private List<UserNodeProvisionDto> nodePermissions;

    private List<UserPortProvisionDto> portPermissions;
}
