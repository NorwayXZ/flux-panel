package com.admin.common.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class VirtualLanCreateDto {
    @NotBlank @Size(max = 100) private String name;
    @NotBlank @Size(max = 32) private String cidr = "10.88.0.0/24";
    @NotNull private Long hubNodeId;
    @NotNull @Min(1) @Max(65535) private Integer listenPort = 51820;
    @Valid @NotEmpty @Size(min = 1, max = 252) private List<Member> members;

    @Data
    public static class Member {
        @NotBlank @Pattern(regexp = "node|connector") private String targetType;
        @NotNull private Long targetId;
    }
}
