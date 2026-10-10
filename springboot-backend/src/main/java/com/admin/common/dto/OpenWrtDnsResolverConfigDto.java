package com.admin.common.dto;

import lombok.Data;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

@Data
public class OpenWrtDnsResolverConfigDto {
    @NotNull(message = "缺少 OpenWrt Agent ID")
    private Long connectorId;
    private Map<String, String> interfaceCarriers;
    private List<Long> smartEntryGroupIds;
}
