package com.admin.common.dto;

import lombok.Data;

/** Public configuration only; credentials and certificate keys never come from the browser. */
@Data
public class CnixSharedRelayDto {
    private Integer servicePort;
    private String externalAddress;
    private String state;
    private String lastError;
}
