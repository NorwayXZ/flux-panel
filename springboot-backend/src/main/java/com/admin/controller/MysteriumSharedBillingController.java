package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import com.admin.service.MysteriumSharedBillingService;
import com.alibaba.fastjson.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/mysterium-shared")
public class MysteriumSharedBillingController {
    private final MysteriumSharedBillingService service;
    public MysteriumSharedBillingController(MysteriumSharedBillingService service){this.service=service;}
    @RequireRole @PostMapping("/overview") public R overview(){return R.ok(service.overview());}
    public record Configure(Long ownerNodeId,List<Long> nodeIds,String endpoint){}
    @RequireRole @PostMapping("/configure") public R configure(@RequestBody Configure body){
        return service.configure(body.ownerNodeId(),body.nodeIds(),body.endpoint());
    }
    // This endpoint has its own revocable per-node token, not a browser JWT.
    @PostMapping("/pay") public ResponseEntity<?> pay(@RequestHeader(value="Authorization",defaultValue="") String authorization,
                                                     @RequestBody JSONObject body){
        if(!authorization.startsWith("Bearer "))return ResponseEntity.status(401).build();
        try{return ResponseEntity.ok(service.pay(authorization.substring(7),body));}
        catch(IllegalArgumentException e){return ResponseEntity.status(401).body(Map.of("error","unauthorized payment request"));}
        catch(Exception e){return ResponseEntity.status(409).body(Map.of("error","shared payment unavailable; check signer status"));}
    }
}
