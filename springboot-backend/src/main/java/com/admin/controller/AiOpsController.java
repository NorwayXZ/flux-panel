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
  private final com.admin.service.AiNodeMemory memory;

  public AiOpsController(AiOpsService service, com.admin.service.AiOpsAutomation automation,
      com.admin.service.AiNodeMemory memory) {
    this.service = service;
    this.automation = automation;
    this.memory = memory;
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
  @PostMapping("/notes")
  public R notes() {
    return memory.listNotes();
  }

  @RequireRole
  @PostMapping("/notes/save")
  public R saveNote(@RequestBody JSONObject body) {
    try {
      return memory.saveNote(body.getLongValue("nodeId"), body.getString("content"));
    } catch (IllegalArgumentException e) {
      return R.err(e.getMessage());
    }
  }

  @RequireRole
  @PostMapping("/changes")
  public R changes(@RequestBody JSONObject body) {
    return memory.changes(body.getLong("nodeId"));
  }

  @RequireRole
  @PostMapping("/changes/restore")
  public R restore(@RequestBody JSONObject body) {
    try {
      return memory.restore(body.getLongValue("id"));
    } catch (Exception e) {
      return R.err("还原失败：" + com.admin.service.AiOpsService.safeErrorPublic(e));
    }
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
