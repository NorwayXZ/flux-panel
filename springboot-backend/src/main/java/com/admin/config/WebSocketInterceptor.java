package com.admin.config;


import com.admin.common.utils.IpUtils;
import com.admin.common.utils.ClientIpUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.entity.InternalConnector;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.service.NodeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;


@Configuration
@Slf4j
public class WebSocketInterceptor extends HttpSessionHandshakeInterceptor {

    @Resource
    NodeService nodeService;

    @Resource
    InternalConnectorMapper internalConnectorMapper;

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception ex) {

    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        ServletServerHttpRequest serverHttpRequest = (ServletServerHttpRequest) request;
        String secret = serverHttpRequest.getServletRequest().getParameter("secret");
        String type = serverHttpRequest.getServletRequest().getParameter("type");
        String version = serverHttpRequest.getServletRequest().getParameter("version");
        String http = serverHttpRequest.getServletRequest().getParameter("http");
        String tls = serverHttpRequest.getServletRequest().getParameter("tls");
        String socks = serverHttpRequest.getServletRequest().getParameter("socks");
        String machine = serverHttpRequest.getServletRequest().getParameter("machine");
        if (Objects.equals(type, "1")) {
            log.info("节点握手请求，类型: {}, 版本: {}, IP: {}", type, version, getClientIp(request));
            Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
            if (node == null) {
                log.info("节点验证失败：未找到匹配的secret");
                return false;
            }
            attributes.put("id", node.getId());
            attributes.put("nodeSecret", secret);
            attributes.put("nodeVersion", version);
            attributes.put("http",http);
            attributes.put("tls",tls);
            attributes.put("socks",socks);
            attributes.put("remoteIp", ClientIpUtil.resolve(serverHttpRequest.getServletRequest()));
            attributes.put("machineFingerprint", machine);
            attributes.put("expectedIp", node.getServerIp());
            log.info("节点 {} 通过验证，版本: {}", node.getId(), version);
            // 不在这里更新状态，等到连接建立后再统一更新
        } else if (Objects.equals(type, "2")) {
            InternalConnector connector = internalConnectorMapper.selectOne(
                    new QueryWrapper<InternalConnector>().eq("secret", secret).eq("status", 1));
            if (connector == null) {
                log.info("内网接入端验证失败：未找到有效密钥");
                return false;
            }
            attributes.put("id", connector.getId());
            attributes.put("nodeSecret", secret);
            attributes.put("nodeVersion", version);
            attributes.put("remoteIp", getClientIp(request));
            log.info("内网接入端 {} 通过验证，版本: {}", connector.getId(), version);
        } else {
            boolean b = JwtUtil.validateToken(secret);
            if (!b) return false;
            attributes.put("id", JwtUtil.getUserIdFromToken(secret));
        }
        attributes.put("type", type);
        return true;
    }

    public String getClientIp(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return null;
    }


}
