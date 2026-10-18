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
  private final com.admin.service.AiOpsAutomation automation;

  public AiOpsController(AiOpsService service, com.admin.service.AiOpsAutomation automation) {
    this.service = service;
    this.automation = automation;
  }

  @RequireRole
  @PostMapping("/preferences")
  public R preferences() {
    return R.ok(automation.preferences());
  }

  @RequireRole
  @PostMapping("/preferences/save")
  public R savePreferences(@RequestBody JSONObject body) {
    return automation.savePreferences(body);
  }

  @RequireRole
  @PostMapping("/reports")
  public R reports() {
    return automation.reports();
  }

  @RequireRole
  @PostMapping("/reports/generate")
  public R generateReport() {
    return automation.generateNow();
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
