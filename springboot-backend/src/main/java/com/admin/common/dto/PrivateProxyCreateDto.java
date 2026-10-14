package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class PrivateProxyCreateDto {
    @NotBlank @Size(max = 100)
    private String name;
    @NotNull
    private Long nodeId;
    @NotBlank @Pattern(regexp = "socks5|http|mixed|shadowsocks|shadowsocks2022|vless_reality|vless_xhttp|vless|vmess|trojan|anytls|hysteria2|tuic|shadowtls|wireguard", message = "不支持的代理类型")
    private String proxyType;
    @Size(max = 128)
    private String bindIp;
    @NotNull @Min(1) @Max(65535)
    private Integer listenPort;
    @Size(max = 64)
    private String authUsername;
    @Size(max = 128)
    private String authPassword;
    @Pattern(regexp = "|aes-128-gcm|aes-256-gcm|chacha20-ietf-poly1305", message = "不支持的 Shadowsocks 加密方式")
    private String cipher;
    @Size(max = 253)
    private String realityServerName;
    @Size(max = 1000)
    private String allowedCidrs;
    @Min(1) @Max(876000)
    private Integer leaseHours;
    @NotNull
    private Boolean permanent;
    @Pattern(regexp = "|node|residential", message = "不支持的出口类型")
    private String exitType;
    @Pattern(regexp = "|[A-Za-z]{2}", message = "出口国家格式不正确")
    private String exitCountry;
    /** Shadowsocks 2022 的加密方式 */
    @Pattern(regexp = "|2022-blake3-aes-128-gcm|2022-blake3-aes-256-gcm|2022-blake3-chacha20-poly1305", message = "不支持的 Shadowsocks 2022 加密方式")
    private String method;
    /** VMess / VLESS 的传输方式 */
    @Pattern(regexp = "|tcp|ws|grpc|httpupgrade", message = "不支持的传输方式")
    private String transport;
    /** VMess / VLESS 是否使用 TLS */
    private Boolean tls;
    /** TLS 证书域名；VMess / VLESS 走 CDN 时也是请求里的 Host */
    @Size(max = 253)
    private String serverName;
    /** Hysteria2 是否开启 salamander 混淆 */
    private Boolean obfs;
    /** ShadowTLS 握手用的真实网站 */
    @Size(max = 253)
    private String handshakeServer;
    /** 客户端连接用的地址（域名或 IP），留空使用节点地址 */
    @Size(max = 253)
    private String connectHost;
}
