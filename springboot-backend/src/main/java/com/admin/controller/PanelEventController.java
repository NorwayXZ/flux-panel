package com.admin.controller;

import com.admin.common.utils.JwtUtil;
import com.admin.service.PanelEventHub;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;

import javax.servlet.http.HttpServletResponse;

@RestController
@CrossOrigin
@RequestMapping("/api/v1")
public class PanelEventController {
    private final PanelEventHub hub;

    public PanelEventController(PanelEventHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletResponse response) {
        // Tell the bundled nginx not to buffer the stream.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        Integer userId = JwtUtil.getUserIdFromToken();
        boolean admin = Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
        return hub.subscribe(userId, admin);
    }
}
