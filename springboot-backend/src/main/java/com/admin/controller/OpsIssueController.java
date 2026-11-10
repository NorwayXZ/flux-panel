package com.admin.controller;
import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.OpsIssueService;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops-issues")
public class OpsIssueController {
    private final OpsIssueService service;
    public OpsIssueController(OpsIssueService service){this.service=service;}
    @RequireRole @PostMapping("/list") public R list(){return service.overview();}
    @RequireRole @PostMapping("/context") public R context(@RequestBody JSONObject body){return R.ok(service.viewContext(body.getLongValue("id")));}
    @RequireRole @LogAnnotation @PostMapping("/note") public R note(@RequestBody JSONObject body){return service.note(body.getLongValue("id"),body.getString("note"));}
    @RequireRole @LogAnnotation @PostMapping("/verify") public R verify(@RequestBody JSONObject body){return service.verify(body.getLongValue("id"));}
}
