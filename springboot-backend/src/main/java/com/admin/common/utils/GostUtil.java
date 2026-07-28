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
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp) + ":" + port);
        if (StringUtils.isNotBlank(admissionName)) service.put("admission", admissionName);

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

        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.send_msg(nodeId, services, "AddService");
    }

    public static GostDto AddShadowsocksProxy(Long nodeId, String serviceName, String bindIp, Integer port,
                                               String cipher, String password, String admissionName) {
        JSONArray services = new JSONArray();
        services.add(createAuthenticatedService(serviceName + "-tcp", bindIp, port, "ss", "tcp",
                cipher, password, admissionName));
        services.add(createAuthenticatedService(serviceName + "-udp", bindIp, port, "ssu", "udp",
                cipher, password, admissionName));
        return WebSocketServer.send_msg(nodeId, services, "AddService");
    }

    public static GostDto AddRealityFrontend(Long nodeId, String serviceName, String bindIp, Integer port,
                                              Integer runtimePort, String admissionName) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp) + ":" + port);
        if (StringUtils.isNotBlank(admissionName)) service.put("admission", admissionName);
        JSONObject handler = new JSONObject();
        handler.put("type", "tcp");
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);
        service.put("forwarder", createForwarder("127.0.0.1:" + runtimePort, "fifo"));
        JSONArray services = new JSONArray();
        services.add(service);
        return WebSocketServer.send_msg(nodeId, services, "AddService");
    }

    private static JSONObject createAuthenticatedService(String name, String bindIp, Integer port,
                                                           String handlerType, String listenerType,
                                                           String username, String password, String admissionName) {
        JSONObject service = new JSONObject();
        service.put("name", name);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp) + ":" + port);
        if (StringUtils.isNotBlank(admissionName)) service.put("admission", admissionName);
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
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        data.put("serverName", serverName);
        return WebSocketServer.send_msg(nodeId, data, "AddRealityRuntime", 120);
    }

    public static GostDto DeleteRealityRuntime(Long nodeId, String runtimeName) {
        JSONObject data = new JSONObject();
        data.put("name", runtimeName);
        return WebSocketServer.send_msg(nodeId, data, "DeleteRealityRuntime", 30);
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
        JSONObject auth = new JSONObject();
        auth.put("username", username);
        auth.put("password", password);
        JSONObject connector = new JSONObject();
        connector.put("type", "socks5");
        connector.put("auth", auth);
        JSONObject dialer = new JSONObject();
        dialer.put("type", "tcp");
        JSONObject node = new JSONObject();
        node.put("name", chainName + "_node");
        node.put("addr", publicAddress);
        node.put("connector", connector);
        node.put("dialer", dialer);
        JSONArray nodes = new JSONArray();
        nodes.add(node);
        JSONObject hop = new JSONObject();
        hop.put("name", chainName + "_hop");
        hop.put("nodes", nodes);
        JSONArray hops = new JSONArray();
        hops.add(hop);
        JSONObject chain = new JSONObject();
        chain.put("name", chainName);
        chain.put("hops", hops);
        return WebSocketServer.sendConnectorMsg(connectorId, chain, "AddChains");
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
        JSONObject data = createChainData(name, remoteAddrs, protocol, interfaceName);
        return WebSocketServer.send_msg(node_id, data, "AddChains");
    }

    public static GostDto UpdateChains(Long node_id, String name, String remoteAddr, String protocol, String interfaceName) {
        return UpdateChains(node_id, name, java.util.Collections.singletonList(remoteAddr), protocol, interfaceName);
    }

    public static GostDto UpdateChains(Long node_id, String name, List<String> remoteAddrs, String protocol, String interfaceName) {
        JSONObject data = createChainData(name, remoteAddrs, protocol, interfaceName);
        JSONObject req = new JSONObject();
        req.put("chain", name + "_chains");
        req.put("data", data);
       return WebSocketServer.send_msg(node_id, req, "UpdateChains");
    }

    private static JSONObject createChainData(String name, List<String> remoteAddrs, String protocol, String interfaceName) {
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
            connector.put("type", "relay");

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
        return data;
    }

    public static GostDto DeleteChains(Long node_id, String name) {
        JSONObject data = new JSONObject();
        data.put("chain", name + "_chains");
        return WebSocketServer.send_msg(node_id, data, "DeleteChains");
    }

    private static JSONObject createLimiterData(Long name, String speed) {
        JSONObject data = new JSONObject();
        data.put("name", name.toString());
        JSONArray limits = new JSONArray();
        limits.add("$ " + speed + "MB " + speed + "MB");
        data.put("limits", limits);
        return data;
    }

    private static JSONObject createServiceConfig(String name, Integer in_port, Integer limiter, String remoteAddr, String protocol, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, String chainName) {
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
        service.put("handler", handler);

        // 配置监听器
        JSONObject listener = createListener(protocol);
        service.put("listener", listener);

        // 端口转发需要配置转发器
        if (isPortForwarding(fow_type)) {
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
