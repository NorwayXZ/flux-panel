package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

@Data
public class MultiLineAggregationDto {
    private Long id;

    @NotBlank(message = "聚合名称不能为空")
    private String name;

    @NotEmpty(message = "至少选择两条线路")
    private List<Long> tunnelIds;

    @Min(value = 1, message = "端口号不能小于1")
    @Max(value = 65535, message = "端口号不能大于65535")
    private Integer listenPort;

    @NotBlank(message = "目标地址不能为空")
    private String remoteAddr;

    private String protocolMode;
    private String mode;
    private Boolean autoWeight;

    @Min(value = 1, message = "最少健康线路不能小于1")
    private Integer minimumHealthyPaths;

    private Boolean enabled;
    private Map<Long, Integer> manualWeights;
}
