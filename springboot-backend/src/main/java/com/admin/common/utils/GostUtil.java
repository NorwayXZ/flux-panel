package com.admin.common.utils;

import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.apache.bcel.generic.RET;

import java.util.Objects;
import java.util.List;

public class GostUtil {


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
