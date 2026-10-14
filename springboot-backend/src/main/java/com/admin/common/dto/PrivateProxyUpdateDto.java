package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/** 修改已有私人代理。只改填写了的项。 */
@Data
public class PrivateProxyUpdateDto {
    @NotNull
    private Long id;
    @Size(max = 100)
    private String name;
    /** 客户端连接用的地址；空字符串表示改回节点地址 */
    @Size(max = 253)
    private String connectHost;
    @Min(1) @Max(65535)
    private Integer listenPort;
    /** 来源 IP 白名单；空字符串表示清空 */
    @Size(max = 1000)
    private String allowedCidrs;
    /** 重新生成密码或密钥，旧的连接信息立即失效 */
    private Boolean resetCredentials;
    /** 续期小时数，从当前到期时间（已过期则从现在）往后加 */
    @Min(1) @Max(876000)
    private Integer renewHours;
    /** 改为永久有效 */
    private Boolean permanent;
    /** 更换 REALITY 伪装站；auto 表示在节点上测速后自动选择 */
    @Size(max = 253)
    private String realityServerName;
    /** 更换 ShadowTLS 握手网站；auto 表示自动选择 */
    @Size(max = 253)
    private String handshakeServer;
}
