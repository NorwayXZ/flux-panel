package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class NetworkDiagnosticDto {
    @NotNull private Long nodeId;
    @NotBlank @Pattern(regexp = "ping|tcp|dns|trace") private String mode;
    @NotBlank @Size(max = 253) private String target;
    @Pattern(regexp = "A|AAAA|CNAME|MX|TXT", message = "DNS 记录类型不受支持")
    private String recordType;
    @Min(1) @Max(65535) private Integer port;
    @Min(1) @Max(10) private Integer count;
    @Min(200) @Max(30000) private Integer timeoutMs;
}
