package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.OpenWrtDnsResolverConfigDto;
import com.admin.common.lang.R;
import com.admin.service.OpenWrtDnsResolverService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/openwrt-dns")
public class OpenWrtDnsController {
    private final OpenWrtDnsResolverService service;
    public OpenWrtDnsController(OpenWrtDnsResolverService service){this.service=service;}
    @PostMapping("/list") @RequireRole public R list(){return service.list();}
    @PostMapping("/options") @RequireRole public R options(){return service.options();}
    @PostMapping("/configure") @RequireRole public R configure(@Validated @RequestBody OpenWrtDnsResolverConfigDto dto){
        try{return service.configure(dto);}catch(IllegalArgumentException e){return R.err(e.getMessage());}
    }
    @PostMapping("/remove") @RequireRole public R remove(@RequestBody Map<String,Long> body){
        if(body.get("connectorId")==null)return R.err("缺少 Agent ID");
        return service.removePolicy(body.get("connectorId"));
    }
}
