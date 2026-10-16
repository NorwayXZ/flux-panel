package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.PrivateProxyCreateDto;
import com.admin.common.dto.PrivateProxyGrantDto;
import com.admin.common.dto.PrivateProxyGrantUpdateDto;
import com.admin.common.dto.PrivateProxyUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.PrivateProxyService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/private-proxy")
public class PrivateProxyController {
    private final PrivateProxyService service;
    public PrivateProxyController(PrivateProxyService service) { this.service = service; }

    @LogAnnotation @PostMapping("/create") public R create(@Validated @RequestBody PrivateProxyCreateDto dto) { return service.create(dto); }
    @RequireRole @LogAnnotation @PostMapping("/grant") public R grant(@Validated @RequestBody PrivateProxyGrantDto dto) { return service.grant(dto); }
    @RequireRole @PostMapping("/grant-list") public R grants(@RequestBody(required = false) Map<String, Object> body) {
        Object value = body == null ? null : body.get("userId");
        return service.grants(value == null ? null : Integer.valueOf(value.toString()));
    }
    @RequireRole @LogAnnotation @PostMapping("/grant-update") public R updateGrant(@Validated @RequestBody PrivateProxyGrantUpdateDto dto) { return service.updateGrant(dto); }
    @RequireRole @LogAnnotation @PostMapping("/grant-reset") public R resetGrant(@RequestBody Map<String, Long> body) { return service.resetGrantFlow(body.get("id")); }
    @PostMapping("/list") public R list() { return service.list(); }
    @LogAnnotation @PostMapping("/update") public R update(@Validated @RequestBody PrivateProxyUpdateDto dto) { return service.update(dto); }
    @PostMapping("/test") public R test(@RequestBody Map<String, Long> body) { return service.test(body.get("id")); }
    @PostMapping("/camouflage-targets") public R camouflageTargets(@RequestBody Map<String, Object> body) {
        Object nodeId = body.get("nodeId");
        Object domains = body.get("domains");
        java.util.List<String> list = new java.util.ArrayList<>();
        if (domains instanceof java.util.List) for (Object domain : (java.util.List<?>) domains) list.add(String.valueOf(domain));
        return service.camouflageTargets(nodeId == null ? null : Long.valueOf(nodeId.toString()), list);
    }
    @PostMapping("/traffic") public R traffic(@RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        return service.traffic(id == null ? null : Long.valueOf(id.toString()), body.get("range") == null ? null : body.get("range").toString());
    }
    @LogAnnotation @PostMapping("/subscription") public R subscription(@RequestBody(required = false) Map<String, Object> body) {
        return service.subscription(body != null && Boolean.TRUE.equals(body.get("reset")));
    }
    @PostMapping("/client-config") public R clientConfig(@RequestBody Map<String, Long> body) { return service.clientConfig(body.get("id")); }
    @RequireRole @LogAnnotation @PostMapping("/exit") public R updateExit(@RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        return service.updateExit(id == null ? null : Long.valueOf(id.toString()),
                body.get("exitType") == null ? null : body.get("exitType").toString(),
                body.get("exitCountry") == null ? null : body.get("exitCountry").toString());
    }
    @SuppressWarnings("unchecked")
    @LogAnnotation @PostMapping("/routing") public R updateRouting(@RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        Object customId = body.get("exitCustomId");
        Object rules = body.get("rules");
        return service.updateRouting(id == null ? null : Long.valueOf(id.toString()),
                body.get("exitType") == null ? null : body.get("exitType").toString(),
                body.get("exitCountry") == null ? null : body.get("exitCountry").toString(),
                customId == null || customId.toString().isBlank() ? null : Long.valueOf(customId.toString()),
                rules instanceof java.util.List ? (java.util.List<Map<String, Object>>) rules : null);
    }
    @LogAnnotation @PostMapping("/exit-switch") public R switchExit(@RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        return service.switchOwnExit(id == null ? null : Long.valueOf(id.toString()));
    }
    @LogAnnotation @PostMapping("/exit-switch-token") public R exitSwitchToken(@RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        return service.exitSwitchToken(id == null ? null : Long.valueOf(id.toString()), Boolean.TRUE.equals(body.get("reset")));
    }
    @LogAnnotation @PostMapping("/pause") public R pause(@RequestBody Map<String, Long> body) { return service.pause(body.get("id")); }
    @LogAnnotation @PostMapping("/resume") public R resume(@RequestBody Map<String, Long> body) { return service.resume(body.get("id")); }
    @LogAnnotation @PostMapping("/delete") public R delete(@RequestBody Map<String, Long> body) { return service.delete(body.get("id")); }
}
