package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.service.AiOpsChatService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai-ops/chat")
public class AiOpsChatController {
  private final AiOpsChatService service;

  public AiOpsChatController(AiOpsChatService service) {
    this.service = service;
  }

  @RequireRole
  @PostMapping("/stop")
  public R stop() {
    service.stop(JwtUtil.getUserIdFromToken());
    return R.ok();
  }

  @RequireRole
  @PostMapping("/list")
  public R list() {
    return R.ok(service.list(JwtUtil.getUserIdFromToken()));
  }

  @RequireRole
  @PostMapping("/detail")
  public R detail(@RequestBody JSONObject body) {
    try {
      return R.ok(service.detail(JwtUtil.getUserIdFromToken(), body.getLongValue("id")));
    } catch (IllegalArgumentException e) {
      return R.err(e.getMessage());
    }
  }

  @RequireRole
  @PostMapping("/rename")
  public R rename(@RequestBody JSONObject body) {
    try {
      service.rename(JwtUtil.getUserIdFromToken(), body.getLongValue("id"), body.getString("title"));
      return R.ok();
    } catch (IllegalArgumentException e) {
      return R.err(e.getMessage());
    }
  }

  @RequireRole
  @PostMapping("/search")
  public R search(@RequestBody JSONObject body) {
    return R.ok(service.search(JwtUtil.getUserIdFromToken(), body.getString("q")));
  }

  @RequireRole
  @PostMapping("/delete")
  public R delete(@RequestBody JSONObject body) {
    try {
      service.delete(JwtUtil.getUserIdFromToken(), body.getLongValue("id"));
      return R.ok();
    } catch (IllegalArgumentException e) {
      return R.err(e.getMessage());
    }
  }

  @RequireRole
  @PostMapping("/send")
  public SseEmitter send(@RequestBody JSONObject body, HttpServletResponse response)
      throws IOException {
    try {
      SseEmitter stream = service.send(JwtUtil.getUserIdFromToken(), body);
      response.setContentType("text/event-stream;charset=UTF-8");
      response.setHeader("X-Accel-Buffering", "no");
      response.setHeader("Cache-Control", "no-cache");
      return stream;
    } catch (IllegalArgumentException e) {
      response.setStatus(400);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write(JSON.toJSONString(R.err(e.getMessage())));
      response.flushBuffer();
      return null;
    }
  }
}
