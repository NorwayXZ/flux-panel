package com.admin.common.dto;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Min;
import javax.validation.constraints.Max;
import java.util.List;

@Data
public class ForwardDto {

    @NotBlank(message = "转发名称不能为空")
    private String name;
    
    @NotNull(message = "隧道ID不能为空")
    private Integer tunnelId;
    
    @NotBlank(message = "远程地址不能为空")
    private String remoteAddr;

    private String strategy;

    private String routeMode;

    private List<Integer> routeTunnelIds;

    private String protocolMode;
    
    /**
     * 入口端口（可选，为空时自动分配）
     */
    @Min(value = 1, message = "端口号不能小于1")
    @Max(value = 65535, message = "端口号不能大于65535")
    private Integer inPort;

    @Min(value = 1, message = "批量结束端口不能小于1")
    @Max(value = 65535, message = "批量结束端口不能大于65535")
    private Integer batchEndPort;

    @Min(value = 1, message = "目标起始端口不能小于1")
    @Max(value = 65535, message = "目标起始端口不能大于65535")
    private Integer targetStartPort;

    private String interfaceName;

}
