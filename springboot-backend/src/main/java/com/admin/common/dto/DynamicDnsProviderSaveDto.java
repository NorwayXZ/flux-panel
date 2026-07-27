package com.admin.common.dto;

import lombok.Data;

@Data
public class DynamicDnsProviderSaveDto {
    private Long id;
    private String name;
    private String provider;
    private String credentialA;
    private String credentialB;
    private Boolean enabled;
}
