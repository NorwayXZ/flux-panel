package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

@Data
public class DomainRoutePoolUpdateDto {
    @NotNull(message = "域名入口不能为空")
    private Long id;
    private String strategy = "round";
    private String sessionAffinity = "none";
    @NotEmpty(message = "后端池至少需要一个成员")
    private List<Map<String, Object>> members;
}
