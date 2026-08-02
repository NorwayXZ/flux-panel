package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class BandwidthTestTaskDto {
    private Long id;
    @NotBlank @Size(max = 100) private String name;
    @NotNull private Long sourceNodeId;
    @NotNull private Long targetNodeId;
    @NotNull @Min(1) @Max(65535) private Integer listenPort = 5201;
    @NotBlank @Pattern(regexp = "upload|download|bidirectional") private String direction = "bidirectional";
    @NotNull @Min(1) @Max(8) private Integer streams = 4;
    @NotNull @Min(1) @Max(30) private Integer durationSeconds = 10;
    @NotNull @Min(16) @Max(2048) private Integer maximumMegabytes = 512;
    @NotNull @Min(1) @Max(365) private Integer retentionDays = 30;
}
