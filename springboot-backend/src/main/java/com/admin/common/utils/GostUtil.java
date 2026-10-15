package com.admin.common.utils;

import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.SniRouteTargetDto;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.apache.bcel.generic.RET;

import java.util.Objects;
import java.util.List;
import java.util.Map;

public class GostUtil {

    public static GostDto AddAdmission(Long nodeId, String name, List<String> cidrs) {
        JSONObject data = new JSONObject();
        data.put("name", name);
        data.put("whitelist", true);
        data.put("matchers", cidrs);
        return WebSocketServer.send_msg(nodeId, data, "AddAdmissions");
    }

    public static GostDto DeleteAdmission(Long nodeId, String name) {
        JSONObject data = new JSONObject();
        data.put("admission", name);
        return WebSocketServer.send_msg(nodeId, data, "DeleteAdmissions");
    }

    public static GostDto AddPrivateProxy(Long nodeId, String serviceName, String proxyType,
                                          String bindIp, Integer port, String username,
                                          String password, String admissionName) {
        return AddPrivateProxy(nodeId, serviceName, proxyType, bindIp, port, username, password, admissionName, null);
    }

    public static GostDto AddPrivateProxy(Long nodeId, String serviceName, String proxyType,
                                          String bindIp, Integer port, String username,
                                          String password, String admissionName, String limiterName) {
        return sendPrivateProxy(nodeId, serviceName, proxyType, bindIp, port, username, password,
                admissionName, limiterName, null, "AddService");
    }

    /**
     * 创建或更新 SOCKS5/HTTP 私人代理。exitInterface 非空时，代理的全部出站连接只走该出口；
     * 出口不可用时连接直接失败，不会改走节点自身的网络。
     */
    public static GostDto sendPrivateProxy(Long nodeId, String serviceName, String proxyType,
                                           String bindIp, Integer port, String username,
                                           String password, String admissionName, String limiterName,
                                           String exitInterface, String command) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp) + ":" + port);
        if (StringUtils.isNotBlank(admissionName)) service.put("admission", admissionName);
        if (StringUtils.isNotBlank(limiterName)) service.put("limiter", limiterName);

        JSONObject auth = new JSONObject();
        auth.put("username", username);
        auth.put("password", password);
        JSONObject handler = new JSONObject();
        handler.put("type", proxyType);
        handler.put("auth", auth);
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);
        if (StringUtils.isNotBlank(exitInterface)) service.put("interface", exitInterface);

        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.send_msg(nodeId, services, command);
    }

    public static GostDto AddPrivateProxyWithChain(Long nodeId, String serviceName, String proxyType,
                                                   String bindIp, Integer port, String username,
                                                   String password, String chainName) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", listenAddress(bindIp, port));
        JSONObject auth = new JSONObject(); auth.put("username", username); auth.put("password", password);
        JSONObject handler = new JSONObject(); handler.put("type", proxyType); handler.put("auth", auth);
        handler.put("chain", chainName + "_chains");
        service.put("handler", handler);
        JSONObject listener = new JSONObject(); listener.put("type", "tcp"); service.put("listener", listener);
        JSONArray services = new JSONArray(); services.add(service);
        return WebSocketServer.send_msg(nodeId, services, "AddService");
    }

    public static GostDto AddShadowsocksProxy(Long nodeId, String serviceName, String bindIp, Integer port,
                                               String cipher, String password, String admissionName) {
        return AddShadowsocksProxy(nodeId, serviceName, bindIp, port, cipher, password, admissionName, null);
    }

    public static GostDto AddShadowsocksProxy(Long nodeId, String serviceName, String bindIp, Integer port,
                                               String cipher, String password, String admissionName, String limiterName) {
        return sendShadowsocksProxy(nodeId, serviceName, bindIp, port, cipher, password, admissionName, limiterName,
                null, "AddService");
    }

    public static GostDto sendShadowsocksProxy(Long nodeId, String serviceName, String bindIp, Integer port,
                                                String cipher, String password, String admissionName, String limiterName,
                                                String exitInterface, String command) {
        JSONArray services = new JSONArray();
        services.add(createAuthenticatedService(serviceName + "-tcp", bindIp, port, "ss", "tcp",
                cipher, password, admissionName, limiterName));
        services.add(createAuthenticatedService(serviceName + "-udp", bindIp, port, "ssu", "udp",
                cipher, password, admissionName, limiterName));
        if (StringUtils.isNotBlank(exitInterface)) {
            for (int i = 0; i < services.size(); i++) services.getJSONObject(i).put("interface", exitInterface);
        }
        return WebSocketServer.send_msg(nodeId, services, command);
    }

    /**
     * 节点本机的出口通道：只监听 127.0.0.1 的 SOCKS5 服务，把交给它的流量从指定家宽出口发出去。
     * 由独立运行时（xray、sing-box）承载的协议通过它使用家宽出口；出口不可用时通道拒绝连接。
     */
    public static GostDto sendExitRoute(Long nodeId, String serviceName, Integer port, String username,
                                        String password, String exitInterface, String command) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", "127.0.0.1:" + port);
        JSONObject auth = new JSONObject();
        auth.put("username", username);
        auth.put("password", password);
        JSONObject metadata = new JSONObject();
        metadata.put("udp", true);
        JSONObject handler = new JSONObject();
        handler.put("type", "socks5");
        handler.put("auth", auth);
        handler.put("metadata", metadata);
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);
        service.put("interface", exitInterface);
        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.send_msg(nodeId, services, command);
    }

    public static GostDto SyncExitPool(Long nodeId, JSONObject payload) {
        return WebSocketServer.send_msg(nodeId, payload, "ExitPoolSync", 10);
    }

    public static GostDto GetExitPoolStatus(Long nodeId, String group) {
        JSONObject data = new JSONObject();
        data.put("group", group);
        return WebSocketServer.send_msg(nodeId, data, "ExitPoolStatus", 8);
    }

    public static GostDto DeleteExitPool(Long nodeId, String group) {
        JSONObject data = new JSONObject();
        data.put("group", group);
        return WebSocketServer.send_msg(nodeId, data, "ExitPoolDelete", 15);
    }

    public static GostDto AddRealityFrontend(Long nodeId, String serviceName, String bindIp, Integer port,
                                              Integer runtimePort, String admissionName) {
        return AddRealityFrontend(nodeId, serviceName, bindIp, port, runtimePort, admissionName, null);
    }

    public static GostDto AddRealityFrontend(Long nodeId, String serviceName, String bindIp, Integer port,
                                              Integer runtimePort, String admissionName, String limiterName) {
        return SendRealityFrontend(nodeId, serviceName, bindIp, port, runtimePort, admissionName, limiterName, "AddService");
    }

    /** command 为 AddService 或 UpdateService。 */
    public static GostDto SendRealityFrontend(Long nodeId, String serviceName, String bindIp, Integer port,
                                              Integer runtimePort, String admissionName, String limiterName,
                                              String command) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp) + ":" + port);
        if (StringUtils.isNotBlank(admissionName)) service.put("admission", admissionName);
        if (StringUtils.isNotBlank(limiterName)) service.put("limiter", limiterName);
        JSONObject handler = new JSONObject();
        handler.put("type", "tcp");
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);
        service.put("forwarder", createForwarder("127.0.0.1:" + runtimePort, "fifo"));
        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.send_msg(nodeId, services, command);
    }

    private static JSONObject createAuthenticatedService(String name, String bindIp, Integer port,
                                                           String handlerType, String listenerType,
                                                           String username, String password, String admissionName,
                                                           String limiterName) {
        JSONObject service = new JSONObject();
        service.put("name", name);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp) + ":" + port);
        if (StringUtils.isNotBlank(admissionName)) service.put("admission", admissionName);
        if (StringUtils.isNotBlank(limiterName)) service.put("limiter", limiterName);
        JSONObject auth = new JSONObject();
        auth.put("username", username);
        auth.put("password", password);
        JSONObject handler = new JSONObject();
        handler.put("type", handlerType);
        handler.put("auth", auth);
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", listenerType);
        service.put("listener", listener);
        return service;
    }

    public static GostDto AddRealityRuntime(Long nodeId, String runtimeName, String serverName) {
        return AddRealityRuntime(nodeId, runtimeName, serverName, null, null, null, null);
    }

    public static GostDto AddRealityRuntime(Long nodeId, String runtimeName, String serverName,
                                            String outboundProxyHost, Integer outboundProxyPort,
                                            String outboundProxyUsername, String outboundProxyPassword) {
        JSONObject data = createRealityRuntimeData(runtimeName, serverName, outboundProxyHost,
                outboundProxyPort, outboundProxyUsername, outboundProxyPassword);
        return WebSocketServer.send_msg(nodeId, data, "AddRealityRuntime", 120);
    }

    /** network 为 xhttp 时创建 VLESS + XHTTP + REALITY，否则为 VLESS + Vision + REALITY。 */
    public static GostDto AddRealityRuntime(Long nodeId, String runtimeName, String serverName, String network,
                                            String path, String outboundProxyHost, Integer outboundProxyPort,
                                            String outboundProxyUsername, String outboundProxyPassword) {
        JSONObject data = createRealityRuntimeData(runtimeName, serverName, outboundProxyHost,
                outboundProxyPort, outboundProxyUsername, outboundProxyPassword);
        if (StringUtils.isNotBlank(network)) {
            data.put("network", network);
            data.put("path", path);
        }
        return WebSocketServer.send_msg(nodeId, data, "AddRealityRuntime", 120);
    }

    public static GostDto AddXHTTPRuntime(Long nodeId, String runtimeName, String clientId, String path,
                                          String mode, String paddingBytes, String security, String serverName,
                                          String certificateFile, String keyFile, String outboundProxyHost,
                                          Integer outboundProxyPort, String outboundProxyUsername, String outboundProxyPassword) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        data.put("clientId", clientId);
        data.put("path", path);
        data.put("mode", mode);
        data.put("xPaddingBytes", paddingBytes);
        data.put("security", security);
        data.put("serverName", serverName);
        data.put("certificateFile", certificateFile);
        data.put("keyFile", keyFile);
        if (outboundProxyPort != null) {
            data.put("outboundProxyHost", StringUtils.defaultIfBlank(outboundProxyHost, "127.0.0.1"));
            data.put("outboundProxyPort", outboundProxyPort);
            data.put("outboundProxyUsername", outboundProxyUsername);
            data.put("outboundProxyPassword", outboundProxyPassword);
        }
        return WebSocketServer.send_msg(nodeId, data, "AddXHTTPRuntime", 120);
    }

    public static GostDto AddXHTTPClientRuntime(Long nodeId, String runtimeName, String remoteHost, Integer remotePort,
                                                String clientId, String path, String mode, String paddingBytes,
                                                String security, String serverName) {
        JSONObject data = new JSONObject(); data.put("name", runtimeName); data.put("remoteHost", remoteHost);
        data.put("remotePort", remotePort); data.put("clientId", clientId); data.put("path", path);
        data.put("mode", mode); data.put("xPaddingBytes", paddingBytes); data.put("security", security); data.put("serverName", serverName);
        return WebSocketServer.send_msg(nodeId, data, "AddXHTTPClientRuntime", 120);
    }

    static JSONObject createRealityRuntimeData(String runtimeName, String serverName,
                                               String outboundProxyHost, Integer outboundProxyPort,
                                               String outboundProxyUsername, String outboundProxyPassword) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        data.put("serverName", serverName);
        if (outboundProxyPort != null) {
            data.put("outboundProxyHost", StringUtils.defaultIfBlank(outboundProxyHost, "127.0.0.1"));
            data.put("outboundProxyPort", outboundProxyPort);
            data.put("outboundProxyUsername", outboundProxyUsername);
            data.put("outboundProxyPassword", outboundProxyPassword);
        }
        return data;
    }

    public static GostDto AddRealityClientRuntime(Long connectorId, String runtimeName, String remoteHost,
                                                   Integer remotePort, String clientId, String publicKey,
                                                   String shortId, String serverName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        data.put("remoteHost", remoteHost);
        data.put("remotePort", remotePort);
        data.put("clientId", clientId);
        data.put("publicKey", publicKey);
        data.put("shortId", shortId);
        data.put("serverName", serverName);
        return WebSocketServer.sendConnectorMsg(connectorId, data, "AddRealityClientRuntime", 120);
    }

    public static GostDto AddNodeRealityClientRuntime(Long nodeId, String runtimeName, String remoteHost,
                                                       Integer remotePort, String clientId, String publicKey,
                                                       String shortId, String serverName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        data.put("remoteHost", remoteHost);
        data.put("remotePort", remotePort);
        data.put("clientId", clientId);
        data.put("publicKey", publicKey);
        data.put("shortId", shortId);
        data.put("serverName", serverName);
        return WebSocketServer.send_msg(nodeId, data, "AddRealityClientRuntime", 120);
    }

    public static GostDto DeleteConnectorRealityRuntime(Long connectorId, String runtimeName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        return WebSocketServer.sendConnectorMsg(connectorId, data, "DeleteRealityRuntime", 30);
    }

    public static GostDto DeleteRealityRuntime(Long nodeId, String runtimeName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        return WebSocketServer.send_msg(nodeId, data, "DeleteRealityRuntime", 30);
    }

    public static GostDto AddPrivateProxyRuntime(Long nodeId, String runtimeName, String proxyType, String bindIp,
                                                 Integer port, String password) {
        return AddPrivateProxyRuntime(nodeId, runtimeName, proxyType, bindIp, port, password, null, null, null);
    }

    /** outboundPort 非空时，运行时的全部出站流量交给本机的出口通道。对已存在的运行时只更新出站方式，密钥不变。 */
    public static GostDto AddPrivateProxyRuntime(Long nodeId, String runtimeName, String proxyType, String bindIp,
                                                 Integer port, String password, Integer outboundPort,
                                                 String outboundUsername, String outboundPassword) {
        JSONObject data = new JSONObject();
        if (outboundPort != null) {
            data.put("outboundProxyPort", outboundPort);
            data.put("outboundProxyUsername", outboundUsername);
            data.put("outboundProxyPassword", outboundPassword);
        }
        data.put("name", runtimeName);
        data.put("proxyType", proxyType);
        data.put("bindIp", StringUtils.defaultString(bindIp));
        data.put("listenPort", port);
        data.put("password", password);
        return WebSocketServer.send_msg(nodeId, data, "AddPrivateProxyRuntime", 120);
    }

    /** options 是协议设置（传输方式、证书名、白名单等），原样交给 Agent。 */
    public static GostDto AddPrivateProxyRuntime(Long nodeId, String runtimeName, String proxyType, String bindIp,
                                                 Integer port, String password, Integer outboundPort,
                                                 String outboundUsername, String outboundPassword, JSONObject options) {
        JSONObject data = new JSONObject();
        if (options != null) data.putAll(options);
        if (outboundPort != null) {
            data.put("outboundProxyPort", outboundPort);
            data.put("outboundProxyUsername", outboundUsername);
            data.put("outboundProxyPassword", outboundPassword);
        }
        data.put("name", runtimeName);
        data.put("proxyType", proxyType);
        data.put("bindIp", StringUtils.defaultString(bindIp));
        data.put("listenPort", port);
        data.put("password", password);
        return WebSocketServer.send_msg(nodeId, data, "AddPrivateProxyRuntime", 120);
    }

    /** 让节点运行一次代理的客户端，经代理打开测试页面，返回看到的出口 IP 和用时。 */
    /** 在节点上检测伪装站：TLS 1.3、X25519、HTTP/2、证书与域名一致，并测握手用时。 */
    public static GostDto ProbeRealityTargets(Long nodeId, List<String> domains) {
        JSONObject data = new JSONObject();
        data.put("domains", domains);
        return WebSocketServer.send_msg(nodeId, data, "RealityTargetProbe", 45);
    }

    public static GostDto ProbePrivateProxy(Long nodeId, JSONObject request) {
        return WebSocketServer.send_msg(nodeId, request, "PrivateProxyProbe", 60);
    }

    public static GostDto DeletePrivateProxyRuntime(Long nodeId, String runtimeName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        return WebSocketServer.send_msg(nodeId, data, "DeletePrivateProxyRuntime", 30);
    }

    public static GostDto PausePrivateProxyRuntime(Long nodeId, String runtimeName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        return WebSocketServer.send_msg(nodeId, data, "PausePrivateProxyRuntime", 30);
    }

    public static GostDto ResumePrivateProxyRuntime(Long nodeId, String runtimeName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        return WebSocketServer.send_msg(nodeId, data, "ResumePrivateProxyRuntime", 120);
    }

    public static GostDto GetPrivateProxyRuntimeTraffic(Long nodeId, String runtimeName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        return WebSocketServer.send_msg(nodeId, data, "PrivateProxyRuntimeTraffic", 5);
    }

    public static GostDto DeleteNamedService(Long nodeId, String serviceName) {
        return DeleteNamedServices(nodeId, List.of(serviceName));
    }

    public static GostDto DeleteNamedServices(Long nodeId, List<String> serviceNames) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.addAll(serviceNames);
        data.put("services", services);
        return WebSocketServer.send_msg(nodeId, data, "DeleteService");
    }

    public static GostDto PauseNamedService(Long nodeId, String serviceName) {
        return PauseNamedServices(nodeId, List.of(serviceName));
    }

    public static GostDto PauseNamedServices(Long nodeId, List<String> serviceNames) {
        JSONObject data = new JSONObject();
        data.put("services", serviceNames);
        return WebSocketServer.send_msg(nodeId, data, "PauseService");
    }

    public static GostDto ResumeNamedService(Long nodeId, String serviceName) {
        return ResumeNamedServices(nodeId, List.of(serviceName));
    }

    public static GostDto ResumeNamedServices(Long nodeId, List<String> serviceNames) {
        JSONObject data = new JSONObject();
        data.put("services", serviceNames);
        return WebSocketServer.send_msg(nodeId, data, "ResumeService");
    }

    public static GostDto ConfigureDomainIngress(Long nodeId, String serviceName, String bindIp, Integer port,
                                                 List<SniRouteTargetDto> targets, boolean update) {
        JSONArray services = new JSONArray();
        services.add(SniDomainUtil.buildIngressService(serviceName, bindIp, port, targets));
        return WebSocketServer.send_msg(nodeId, services, update ? "UpdateService" : "AddService");
    }

    public static GostDto DeleteDomainIngress(Long nodeId, String serviceName) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(serviceName);
        data.put("services", services);
        return WebSocketServer.send_msg(nodeId, data, "DeleteService");
    }

    public static GostDto DeployCertificates(Long nodeId, List<Map<String, Object>> certificates) {
        JSONObject payload = new JSONObject();
        payload.put("certificates", certificates);
        return WebSocketServer.send_msg(nodeId, payload, "DeployCertificates");
    }

    public static GostDto ConfigureManagedHttpsIngress(Long nodeId, String serviceName, String bindIp, Integer port,
                                                       List<SniRouteTargetDto> targets,
                                                       List<Map<String, Object>> certificates, boolean update) {
        JSONArray services = new JSONArray();
        services.add(SniDomainUtil.buildManagedHttpsService(serviceName, bindIp, port, targets, certificates));
        return WebSocketServer.send_msg(nodeId, services, update ? "UpdateService" : "AddService");
    }

    public static GostDto AddPublishingGateway(Long nodeId, String name, String bindIp, Integer port,
                                               String username, String password) {
        JSONObject service = new JSONObject();
        service.put("name", name);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp) + ":" + port);

        JSONObject auth = new JSONObject();
        auth.put("username", username);
        auth.put("password", password);
        JSONObject metadata = new JSONObject();
        metadata.put("bind", true);
        metadata.put("bindOnly", true);
        metadata.put("udp", false);
        JSONObject handler = new JSONObject();
        handler.put("type", "socks5");
        handler.put("auth", auth);
        handler.put("metadata", metadata);
        service.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);

        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.send_msg(nodeId, services, "AddService");
    }

    public static GostDto DeletePublishingGateway(Long nodeId, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name);
        data.put("services", services);
        return WebSocketServer.send_msg(nodeId, data, "DeleteService");
    }

    public static GostDto AddHomeEgressGateway(Long nodeId, String name, String bindIp, Integer port,
                                               String username, String password) {
        JSONObject service = new JSONObject();
        service.put("name", name);
        service.put("addr", listenAddress(bindIp, port));
        JSONObject auth = new JSONObject();
        auth.put("username", username);
        auth.put("password", password);
        JSONObject handler = new JSONObject();
        handler.put("type", "socks5");
        handler.put("auth", auth);
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);
        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.send_msg(nodeId, services, "AddService");
    }

    public static GostDto AddPublishingChain(Long connectorId, String chainName, String publicAddress,
                                             String username, String password) {
        return AddPublishingChain(connectorId, chainName,
                List.of(new PublishingProxyHop(publicAddress, username, password)));
    }

    public static GostDto AddPublishingChain(Long connectorId, String chainName,
                                             List<PublishingProxyHop> proxyHops) {
        return WebSocketServer.sendConnectorMsg(connectorId,
                createPublishingChainData(chainName, proxyHops), "AddChains");
    }

    static JSONObject createPublishingChainData(String chainName, List<PublishingProxyHop> proxyHops) {
        JSONArray hops = new JSONArray();
        int index = 1;
        for (PublishingProxyHop proxyHop : proxyHops) {
            JSONObject connector = new JSONObject();
            connector.put("type", "socks5");
            if (StringUtils.isNotBlank(proxyHop.username()) || StringUtils.isNotBlank(proxyHop.password())) {
                JSONObject auth = new JSONObject();
                auth.put("username", proxyHop.username());
                auth.put("password", proxyHop.password());
                connector.put("auth", auth);
            }
            JSONObject dialer = new JSONObject();
            dialer.put("type", "tcp");
            JSONObject node = new JSONObject();
            node.put("name", chainName + "_node_" + index);
            node.put("addr", proxyHop.address());
            node.put("connector", connector);
            node.put("dialer", dialer);
            JSONArray nodes = new JSONArray();
            nodes.add(node);
            JSONObject hop = new JSONObject();
            hop.put("name", chainName + "_hop_" + index);
            hop.put("nodes", nodes);
            hops.add(hop);
            index++;
        }
        JSONObject chain = new JSONObject();
        chain.put("name", chainName);
        chain.put("hops", hops);
        return chain;
    }

    public record PublishingProxyHop(String address, String username, String password) {
    }

    public static GostDto AddPublishedTcpService(Long connectorId, String serviceName, String chainName,
                                                 String bindIp, Integer publicPort, String targetAddress) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp) + ":" + publicPort);
        JSONObject handler = new JSONObject();
        handler.put("type", "rtcp");
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", "rtcp");
        listener.put("chain", chainName);
        service.put("listener", listener);
        service.put("forwarder", createForwarder(targetAddress, "fifo"));
        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.sendConnectorMsg(connectorId, services, "AddService");
    }

    public static GostDto DeletePublishedTcpService(Long connectorId, String serviceName, String chainName) {
        JSONObject serviceData = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(serviceName);
        serviceData.put("services", services);
        GostDto serviceResult = WebSocketServer.sendConnectorMsg(connectorId, serviceData, "DeleteService");

        JSONObject chainData = new JSONObject();
        chainData.put("chain", chainName);
        GostDto chainResult = WebSocketServer.sendConnectorMsg(connectorId, chainData, "DeleteChains");
        if (serviceResult != null && "OK".equals(serviceResult.getMsg())) {
            return chainResult;
        }
        return serviceResult;
    }

    /**
     * Creates a SOCKS5 endpoint that is exposed through a reverse TCP chain.
     * The endpoint runs on the home connector, while the public listener is
     * allocated on the ingress pool gateway.
     */
    public static GostDto AddHomeProxyService(Long connectorId, String serviceName, String ingressChainName,
                                               String egressChainName, String bindIp, Integer publicPort,
                                               boolean authEnabled, String username, String password) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", listenAddress(bindIp, publicPort));

        JSONObject handler = new JSONObject();
        handler.put("type", "socks5");
        handler.put("chain", egressChainName);
        if (authEnabled) {
            JSONObject auth = new JSONObject();
            auth.put("username", username);
            auth.put("password", password);
            handler.put("auth", auth);
        }
        service.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", "rtcp");
        listener.put("chain", ingressChainName);
        service.put("listener", listener);

        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.sendConnectorMsg(connectorId, services, "AddService");
    }

    /** Creates a SOCKS5 listener directly on the connector's public IPv6 address. */
    public static GostDto AddDirectHomeProxyService(Long connectorId, String serviceName, String egressChainName,
                                                     Integer directPort, boolean authEnabled,
                                                     String username, String password) {
        return AddDirectHomeProxyService(connectorId, serviceName, egressChainName, "::", directPort,
                authEnabled, username, password);
    }

    /** Creates a SOCKS5 listener directly on the connector's public address. */
    public static GostDto AddDirectHomeProxyService(Long connectorId, String serviceName, String egressChainName,
                                                     String bindIp, Integer directPort, boolean authEnabled,
                                                     String username, String password) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", listenAddress(bindIp, directPort));

        JSONObject handler = new JSONObject();
        handler.put("type", "socks5");
        handler.put("chain", egressChainName);
        if (authEnabled) {
            JSONObject auth = new JSONObject();
            auth.put("username", username);
            auth.put("password", password);
            handler.put("auth", auth);
        }
        service.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);

        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.sendConnectorMsg(connectorId, services, "AddService");
    }

    private static String listenAddress(String bindIp, Integer port) {
        String host = StringUtils.trimToEmpty(bindIp);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return host + ":" + port;
    }

    public static GostDto DeleteHomeProxyService(Long connectorId, String serviceName,
                                                 String ingressChainName, String egressChainName) {
        JSONObject serviceData = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(serviceName);
        serviceData.put("services", services);
        GostDto serviceResult = WebSocketServer.sendConnectorMsg(connectorId, serviceData, "DeleteService");

        JSONObject ingressData = new JSONObject();
        ingressData.put("chain", ingressChainName);
        GostDto ingressResult = WebSocketServer.sendConnectorMsg(connectorId, ingressData, "DeleteChains");

        JSONObject egressData = new JSONObject();
        egressData.put("chain", egressChainName);
        GostDto egressResult = WebSocketServer.sendConnectorMsg(connectorId, egressData, "DeleteChains");

        if (serviceResult != null && "OK".equals(serviceResult.getMsg())) {
            return ingressResult != null && "OK".equals(ingressResult.getMsg()) ? egressResult : ingressResult;
        }
        return serviceResult;
    }

    public static GostDto DeleteDirectHomeProxyService(Long connectorId, String serviceName,
                                                        String egressChainName) {
        JSONObject serviceData = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(serviceName);
        serviceData.put("services", services);
        GostDto serviceResult = WebSocketServer.sendConnectorMsg(connectorId, serviceData, "DeleteService");

        JSONObject chainData = new JSONObject();
        chainData.put("chain", egressChainName);
        GostDto chainResult = WebSocketServer.sendConnectorMsg(connectorId, chainData, "DeleteChains");
        return serviceResult != null && "OK".equals(serviceResult.getMsg()) ? chainResult : serviceResult;
    }

    public static GostDto DeleteConnectorService(Long connectorId, String serviceName) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(serviceName);
        data.put("services", services);
        return WebSocketServer.sendConnectorMsg(connectorId, data, "DeleteService");
    }


    public static GostDto AddLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        return WebSocketServer.send_msg(node_id, data, "AddLimiters");
    }

    public static GostDto UpdateLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        req.put("data", data);
        return WebSocketServer.send_msg(node_id, req, "UpdateLimiters");
    }

    public static GostDto DeleteLimiters(Long node_id, Long name) {
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        return WebSocketServer.send_msg(node_id, req, "DeleteLimiters");
    }

    public static GostDto AddNamedLimiter(Long nodeId, String name, String speed) {
        return WebSocketServer.send_msg(nodeId, createLimiterData(name, speed), "AddLimiters");
    }

    public static GostDto UpdateNamedLimiter(Long nodeId, String name, String speed) {
        JSONObject req = new JSONObject();
        req.put("limiter", name);
        req.put("data", createLimiterData(name, speed));
        return WebSocketServer.send_msg(nodeId, req, "UpdateLimiters");
    }

    public static GostDto DeleteNamedLimiter(Long nodeId, String name) {
        JSONObject req = new JSONObject();
        req.put("limiter", name);
        return WebSocketServer.send_msg(nodeId, req, "DeleteLimiters");
    }

    public static GostDto AddService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName) {
        return AddService(node_id, name, in_port, limiter, remoteAddr, fow_type, tunnel, strategy, interfaceName, "tcp_udp", name);
    }

    public static GostDto AddService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, String protocolMode, String chainName) {
        JSONArray services = new JSONArray();
        for (String protocol : protocolsForMode(protocolMode)) {
            JSONObject service = createServiceConfig(name, in_port, limiter, remoteAddr, protocol, fow_type, tunnel, strategy, interfaceName, chainName);
            services.add(service);
        }
        return WebSocketServer.send_msg(node_id, services, "AddService");
    }

    public static GostDto AddBalancedService(Long nodeId, String name, Integer inPort, Integer limiter,
                                              String remoteAddr, Tunnel tunnel, String targetStrategy,
                                              String interfaceName, String protocolMode,
                                              List<String> chainNames, String routeStrategy) {
        return sendBalancedService(nodeId, name, inPort, limiter, remoteAddr, tunnel, targetStrategy,
                interfaceName, protocolMode, chainNames, routeStrategy, "AddService");
    }

    public static GostDto UpdateBalancedService(Long nodeId, String name, Integer inPort, Integer limiter,
                                                 String remoteAddr, Tunnel tunnel, String targetStrategy,
                                                 String interfaceName, String protocolMode,
                                                 List<String> chainNames, String routeStrategy) {
        return sendBalancedService(nodeId, name, inPort, limiter, remoteAddr, tunnel, targetStrategy,
                interfaceName, protocolMode, chainNames, routeStrategy, "UpdateService");
    }

    private static GostDto sendBalancedService(Long nodeId, String name, Integer inPort, Integer limiter,
                                                String remoteAddr, Tunnel tunnel, String targetStrategy,
                                                String interfaceName, String protocolMode,
                                                List<String> chainNames, String routeStrategy, String operation) {
        JSONArray services = new JSONArray();
        for (String protocol : protocolsForMode(protocolMode)) {
            JSONObject service = createServiceConfig(name, inPort, limiter, remoteAddr, protocol,
                    tunnel.getType(), tunnel, targetStrategy, interfaceName, name);
            JSONObject handler = service.getJSONObject("handler");
            handler.remove("chain");
            JSONObject group = new JSONObject();
            JSONArray chains = new JSONArray();
            for (String chainName : chainNames) chains.add(chainName + "_chains");
            group.put("chains", chains);
            JSONObject selector = new JSONObject();
            selector.put("strategy", normalizeRouteStrategy(routeStrategy));
            selector.put("maxFails", 1);
            selector.put("failTimeout", "30s");
            group.put("selector", selector);
            handler.put("chainGroup", group);
            services.add(service);
        }
        return WebSocketServer.send_msg(nodeId, services, operation);
    }

    public static GostDto UpdateService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName) {
        return UpdateService(node_id, name, in_port, limiter, remoteAddr, fow_type, tunnel, strategy, interfaceName, "tcp_udp", name);
    }

    public static GostDto UpdateService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, String protocolMode, String chainName) {
        JSONArray services = new JSONArray();
        for (String protocol : protocolsForMode(protocolMode)) {
            JSONObject service = createServiceConfig(name, in_port, limiter, remoteAddr, protocol, fow_type, tunnel, strategy, interfaceName, chainName);
            services.add(service);
        }
        return WebSocketServer.send_msg(node_id, services, "UpdateService");
    }

    public static GostDto DeleteService(Long node_id, String name) {
        return DeleteService(node_id, name, "tcp_udp");
    }

    public static GostDto DeleteService(Long node_id, String name, String protocolMode) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        for (String protocol : protocolsForMode(protocolMode)) {
            services.add(name + "_" + protocol);
        }
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "DeleteService");
    }

    public static GostDto AddRemoteService(Long node_id, String name, Integer out_port, String remoteAddr,  String protocol, String strategy, String interfaceName) {
        return sendRemoteService(node_id, createRemoteServiceData(name, out_port, remoteAddr, protocol, strategy, interfaceName, true), "AddService");
    }

    public static GostDto AddRelayService(Long node_id, String name, Integer out_port, String protocol, String interfaceName) {
        return sendRemoteService(node_id, createRemoteServiceData(name, out_port, null, protocol, null, interfaceName, false), "AddService");
    }

    public static GostDto UpdateRemoteService(Long node_id, String name, Integer out_port, String remoteAddr,String protocol, String strategy, String interfaceName) {
        return sendRemoteService(node_id, createRemoteServiceData(name, out_port, remoteAddr, protocol, strategy, interfaceName, true), "UpdateService");
    }

    public static GostDto UpdateRelayService(Long node_id, String name, Integer out_port, String protocol, String interfaceName) {
        return sendRemoteService(node_id, createRemoteServiceData(name, out_port, null, protocol, null, interfaceName, false), "UpdateService");
    }

    private static GostDto sendRemoteService(Long node_id, JSONObject data, String method) {
        JSONArray services = new JSONArray();
        services.add(data);
        return WebSocketServer.send_msg(node_id, services, method);
    }

    private static JSONObject createRemoteServiceData(String name, Integer out_port, String remoteAddr, String protocol, String strategy, String interfaceName, boolean withForwarder) {
        JSONObject data = new JSONObject();
        data.put("name", name + "_tls");
        data.put("addr", ":" + out_port);

        if (StringUtils.isNotBlank(interfaceName)) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", interfaceName);
            data.put("metadata", metadata);
        }


        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        data.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        data.put("listener", listener);

        if (withForwarder) {
            JSONObject forwarder = createForwarder(remoteAddr, strategy);
            data.put("forwarder", forwarder);
        }
        return data;
    }

    public static GostDto DeleteRemoteService(Long node_id, String name) {
        JSONArray data = new JSONArray();
        data.add(name + "_tls");
        JSONObject req = new JSONObject();
        req.put("services", data);
        return WebSocketServer.send_msg(node_id, req, "DeleteService");
    }

    public static GostDto PauseService(Long node_id, String name) {
        return PauseService(node_id, name, "tcp_udp");
    }

    public static GostDto PauseService(Long node_id, String name, String protocolMode) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        for (String protocol : protocolsForMode(protocolMode)) {
            services.add(name + "_" + protocol);
        }
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "PauseService");
    }

    public static GostDto ResumeService(Long node_id, String name) {
        return ResumeService(node_id, name, "tcp_udp");
    }

    public static GostDto ResumeService(Long node_id, String name, String protocolMode) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        for (String protocol : protocolsForMode(protocolMode)) {
            services.add(name + "_" + protocol);
        }
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "ResumeService");
    }

    public static GostDto PauseRemoteService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tls");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "PauseService");
    }

    public static GostDto ResumeRemoteService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tls");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "ResumeService");
    }

    public static GostDto AddChains(Long node_id, String name, String remoteAddr, String protocol, String interfaceName) {
        return AddChains(node_id, name, java.util.Collections.singletonList(remoteAddr), protocol, interfaceName);
    }

    public static GostDto AddChains(Long node_id, String name, List<String> remoteAddrs, String protocol, String interfaceName) {
        JSONObject data = createChainData(name, remoteAddrs, protocol, interfaceName, null);
        return WebSocketServer.send_msg(node_id, data, "AddChains");
    }

    public static GostDto AddWeightedChains(Long nodeId, String name, List<String> remoteAddrs,
                                             String protocol, String interfaceName, Integer weight) {
        return WebSocketServer.send_msg(nodeId,
                createChainData(name, remoteAddrs, protocol, interfaceName, weight), "AddChains");
    }

    public static GostDto AddRoutedChains(Long nodeId, String name, List<List<String>> hopCandidates,
                                          String protocol, String interfaceName) {
        return WebSocketServer.send_msg(nodeId, createRoutedChainData(name, hopCandidates, protocol, interfaceName, null), "AddChains");
    }

    public static GostDto AddWeightedRoutedChains(Long nodeId, String name, List<List<String>> hopCandidates,
                                                  String protocol, String interfaceName, Integer weight) {
        return WebSocketServer.send_msg(nodeId, createRoutedChainData(name, hopCandidates, protocol, interfaceName, weight), "AddChains");
    }

    public static GostDto UpdateRoutedChains(Long nodeId, String name, List<List<String>> hopCandidates,
                                             String protocol, String interfaceName) {
        JSONObject request = new JSONObject();
        request.put("chain", name + "_chains");
        request.put("data", createRoutedChainData(name, hopCandidates, protocol, interfaceName, null));
        return WebSocketServer.send_msg(nodeId, request, "UpdateChains");
    }

    public static GostDto UpdateChains(Long node_id, String name, String remoteAddr, String protocol, String interfaceName) {
        return UpdateChains(node_id, name, java.util.Collections.singletonList(remoteAddr), protocol, interfaceName);
    }

    public static GostDto UpdateChains(Long node_id, String name, List<String> remoteAddrs, String protocol, String interfaceName) {
        JSONObject data = createChainData(name, remoteAddrs, protocol, interfaceName, null);
        JSONObject req = new JSONObject();
        req.put("chain", name + "_chains");
        req.put("data", data);
       return WebSocketServer.send_msg(node_id, req, "UpdateChains");
    }

    /**
     * Creates a direct TCP chain used by a source-IP entry. The backend address is
     * an existing ordinary TCP forward listener, so the chain must preserve the
     * stream and must not send a GOST relay handshake to that listener.
     */
    public static GostDto AddSourceIpChain(Long nodeId, String chainName, String backendAddress) {
        return WebSocketServer.send_msg(nodeId, createSourceIpChainData(chainName, backendAddress), "AddChains");
    }

    public static GostDto UpdateSourceIpChain(Long nodeId, String chainName, String backendAddress) {
        JSONObject data = createSourceIpChainData(chainName, backendAddress);
        JSONObject request = new JSONObject();
        request.put("chain", chainName + "_chains");
        request.put("data", data);
        return WebSocketServer.send_msg(nodeId, request, "UpdateChains");
    }

    public static GostDto ConfigureSourceIpEntry(Long nodeId, String serviceName, String bindIp, Integer port,
                                                  List<SourceIpRoute> routes, String defaultChain,
                                                  boolean update) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", listenAddress(bindIp, port));

        JSONObject handler = new JSONObject();
        handler.put("type", "tcp");
        JSONObject chainGroup = new JSONObject();
        JSONArray chains = new JSONArray();
        JSONArray sourceRoutes = new JSONArray();
        for (SourceIpRoute route : routes) {
            if (route == null || StringUtils.isBlank(route.chainName())) continue;
            String registeredChain = route.chainName() + "_chains";
            chains.add(registeredChain);
            if (route.cidrs() != null && !route.cidrs().isEmpty()) {
                JSONObject sourceRoute = new JSONObject();
                sourceRoute.put("chain", registeredChain);
                sourceRoute.put("cidrs", route.cidrs());
                sourceRoutes.add(sourceRoute);
            }
        }
        chainGroup.put("chains", chains);
        chainGroup.put("default", StringUtils.isBlank(defaultChain) ? "" : defaultChain + "_chains");
        chainGroup.put("sourceRoutes", sourceRoutes);
        JSONObject selector = new JSONObject();
        selector.put("strategy", "round");
        selector.put("maxFails", 1);
        selector.put("failTimeout", "30s");
        chainGroup.put("selector", selector);
        handler.put("chainGroup", chainGroup);
        service.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);

        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.send_msg(nodeId, services, update ? "UpdateService" : "AddService");
    }

    public static GostDto DeleteSourceIpEntry(Long nodeId, String serviceName) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(serviceName);
        data.put("services", services);
        return WebSocketServer.send_msg(nodeId, data, "DeleteService");
    }

    public static GostDto DeleteSourceIpChain(Long nodeId, String chainName) {
        return DeleteChains(nodeId, chainName);
    }

    public record SourceIpRoute(String chainName, List<String> cidrs) {
    }

    static JSONObject createSourceIpChainData(String name, String backendAddress) {
        return createChainData(name, java.util.Collections.singletonList(backendAddress), "tcp", null, null, "tcp");
    }

    private static JSONObject createChainData(String name, List<String> remoteAddrs, String protocol, String interfaceName, Integer weight) {
        return createChainData(name, remoteAddrs, protocol, interfaceName, weight, "relay");
    }

    private static JSONObject createChainData(String name, List<String> remoteAddrs, String protocol,
                                              String interfaceName, Integer weight, String connectorType) {
        JSONArray hops = new JSONArray();
        int index = 1;
        for (String remoteAddr : remoteAddrs) {
            JSONObject dialer = new JSONObject();
            dialer.put("type", protocol);
            if (Objects.equals(protocol, "quic")){
                JSONObject metadata = new JSONObject();
                metadata.put("keepAlive", true);
                metadata.put("ttl", "10s");
                dialer.put("metadata", metadata);
            }

            JSONObject connector = new JSONObject();
            connector.put("type", connectorType);

            JSONObject node = new JSONObject();
            node.put("name", "node-" + name + "-" + index);
            node.put("addr", remoteAddr);
            node.put("connector", connector);
            node.put("dialer", dialer);

            if (StringUtils.isNotBlank(interfaceName)) {
                node.put("interface", interfaceName);
            }

            JSONArray nodes = new JSONArray();
            nodes.add(node);

            JSONObject hop = new JSONObject();
            hop.put("name", "hop-" + name + "-" + index);
            hop.put("nodes", nodes);

            hops.add(hop);
            index++;
        }

        JSONObject data = new JSONObject();
        data.put("name", name + "_chains");
        data.put("hops", hops);
        if (weight != null) {
            JSONObject metadata = new JSONObject();
            metadata.put("weight", Math.max(1, Math.min(1000, weight)));
            data.put("metadata", metadata);
        }
        return data;
    }

    static JSONObject createRoutedChainData(String name, List<List<String>> hopCandidates, String protocol,
                                            String interfaceName, Integer weight) {
        JSONArray hops = new JSONArray();
        int hopIndex = 1;
        for (List<String> candidates : hopCandidates) {
            JSONArray nodes = new JSONArray();
            int nodeIndex = 1;
            for (String address : candidates) {
                JSONObject dialer = new JSONObject();
                dialer.put("type", protocol);
                if (Objects.equals(protocol, "quic")) {
                    JSONObject metadata = new JSONObject(); metadata.put("keepAlive", true); metadata.put("ttl", "10s");
                    dialer.put("metadata", metadata);
                }
                JSONObject connector = new JSONObject(); connector.put("type", "relay");
                JSONObject node = new JSONObject();
                node.put("name", "node-" + name + "-" + hopIndex + "-" + nodeIndex++);
                node.put("addr", address); node.put("connector", connector); node.put("dialer", dialer);
                if (StringUtils.isNotBlank(interfaceName)) node.put("interface", interfaceName);
                nodes.add(node);
            }
            JSONObject selector = new JSONObject(); selector.put("strategy", "fifo"); selector.put("maxFails", 1); selector.put("failTimeout", 30_000_000_000L);
            JSONObject hop = new JSONObject(); hop.put("name", "hop-" + name + "-" + hopIndex++); hop.put("nodes", nodes); hop.put("selector", selector);
            hops.add(hop);
        }
        JSONObject data = new JSONObject(); data.put("name", name + "_chains"); data.put("hops", hops);
        if (weight != null) { JSONObject metadata = new JSONObject(); metadata.put("weight", Math.max(1, Math.min(1000, weight))); data.put("metadata", metadata); }
        return data;
    }

    private static String normalizeRouteStrategy(String strategy) {
        if (Objects.equals(strategy, "rand") || Objects.equals(strategy, "weighted")) return "rand";
        if (Objects.equals(strategy, "hash")) return "hash";
        return "round";
    }

    public static GostDto DeleteChains(Long node_id, String name) {
        JSONObject data = new JSONObject();
        data.put("chain", name + "_chains");
        return WebSocketServer.send_msg(node_id, data, "DeleteChains");
    }

    private static JSONObject createLimiterData(Long name, String speed) {
        return createLimiterData(name.toString(), speed);
    }

    static JSONObject createLimiterData(String name, String speed) {
        JSONObject data = new JSONObject();
        data.put("name", name);
        JSONArray limits = new JSONArray();
        limits.add("$ " + speed + "MB " + speed + "MB");
        data.put("limits", limits);
        return data;
    }

    static JSONObject createServiceConfig(String name, Integer in_port, Integer limiter, String remoteAddr, String protocol, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, String chainName) {
        JSONObject service = new JSONObject();
        service.put("name", name + "_" + protocol);
        if (Objects.equals(protocol, "tcp")){
            service.put("addr", tunnel.getTcpListenAddr() + ":" + in_port);
        }else {
            service.put("addr", tunnel.getUdpListenAddr() + ":" + in_port);
        }

        if (StringUtils.isNotBlank(interfaceName)) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", interfaceName);
            service.put("metadata", metadata);
        }


        // 添加限流器配置
        if (limiter != null) {
            service.put("limiter", limiter.toString());
        }

        // 配置处理器
        JSONObject handler = createHandler(protocol, chainName, fow_type);
        if(tunnel!=null && tunnel.getSharedRelay()!=null) handler.put("metadata",Map.of("cnix.remoteResolution",true));
        service.put("handler", handler);

        // 配置监听器
        JSONObject listener = createListener(protocol);
        service.put("listener", listener);

        // 端口转发需要配置转发器
        if (isPortForwarding(fow_type) || (tunnel != null && tunnel.getSharedRelay() != null)) {
            JSONObject forwarder = createForwarder(remoteAddr, strategy);
            service.put("forwarder", forwarder);
        }
        return service;
    }

    private static JSONObject createHandler(String protocol, String chainName, Integer fow_type) {
        JSONObject handler = new JSONObject();
        handler.put("type", protocol);

        // 隧道转发需要添加链配置
        if (isTunnelForwarding(fow_type)) {
            handler.put("chain", chainName + "_chains");
        }

        return handler;
    }

    private static JSONObject createListener(String protocol) {
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        if (Objects.equals(protocol, "udp")){
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            listener.put("metadata", metadata);
        }
        return listener;
    }

    private static JSONObject createForwarder(String remoteAddr, String strategy) {
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num );
            node.put("addr", addr);
            nodes.add(node);
            num ++;
        }

        if (strategy == null || strategy.equals("")){
            strategy = "fifo";
        }

        forwarder.put("nodes", nodes);

        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);
        return forwarder;
    }

    private static boolean isPortForwarding(Integer fow_type) {
        return fow_type != null && fow_type == 1;
    }

    private static boolean isTunnelForwarding(Integer fow_type) {
        return fow_type != null && fow_type != 1;
    }

    private static String[] protocolsForMode(String protocolMode) {
        if (Objects.equals(protocolMode, "tcp")) {
            return new String[]{"tcp"};
        }
        if (Objects.equals(protocolMode, "udp")) {
            return new String[]{"udp"};
        }
        return new String[]{"tcp", "udp"};
    }

}
