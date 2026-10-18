package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import com.admin.service.AiOpsService;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-ops")
public class AiOpsController {
  private final AiOpsService service;

  public AiOpsController(AiOpsService service) {
    this.service = service;
  }

  @RequireRole
  @PostMapping("/overview")
  public R overview() {
    return service.overview();
  }

  @RequireRole
  @PostMapping("/settings")
  public R settings(@RequestBody JSONObject body) {
    return service.settings(body);
  }

  @RequireRole
  @PostMapping("/save")
  public R save(@RequestBody JSONObject body) {
    return service.save(body);
  }

  @RequireRole
  @PostMapping("/run")
  public R run(@RequestBody JSONObject body) {
    return service.run(body.getLongValue("id"));
  }

  @RequireRole
  @PostMapping("/delete")
  public R delete(@RequestBody JSONObject body) {
    return service.delete(body.getLongValue("id"));
  }

  @RequireRole
  @PostMapping("/detail")
  public R detail(@RequestBody JSONObject body) {
    return service.detail(body.getLongValue("id"));
  }
}
