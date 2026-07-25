package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.service.LayoutPreferenceService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/layout")
public class LayoutPreferenceController {

    @Resource
    private LayoutPreferenceService layoutPreferenceService;

    @PostMapping("/order")
    public R getOrder(@RequestBody Map<String, Object> params) {
        try {
            String scope = params.get("scope") == null ? null : params.get("scope").toString();
            return R.ok(layoutPreferenceService.getOrder(JwtUtil.getUserIdFromToken(), scope));
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }

    @PostMapping("/order/save")
    public R saveOrder(@RequestBody Map<String, Object> params) {
        try {
            String scope = params.get("scope") == null ? null : params.get("scope").toString();
            Object orderValue = params.get("order");
            if (!(orderValue instanceof List<?>)) {
                return R.err("卡片顺序不能为空");
            }
            return R.ok(layoutPreferenceService.saveOrder(
                    JwtUtil.getUserIdFromToken(),
                    scope,
                    (List<?>) orderValue
            ));
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }
}
