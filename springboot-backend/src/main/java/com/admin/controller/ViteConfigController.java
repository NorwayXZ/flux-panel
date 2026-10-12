package com.admin.controller;


import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <p>
 *  网站配置控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-07-24
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/config")
public class ViteConfigController extends BaseController {

    /**
     * Settings the login page and customer views need. Everything else,
     * including the Agent communication address, is for administrators only.
     */
    static final Set<String> PUBLIC_CONFIG_NAMES = Set.of("app_name", "captcha_enabled", "captcha_type");

    static boolean isPublicConfigName(String name) {
        return name != null && PUBLIC_CONFIG_NAMES.contains(name);
    }

    static Map<String, String> visibleConfigs(Map<String, String> configs, boolean admin) {
        if (admin) return configs;
        Map<String, String> visible = new HashMap<>();
        configs.forEach((name, value) -> {
            if (isPublicConfigName(name)) visible.put(name, value);
        });
        return visible;
    }

    /**
     * 获取网站配置
     * 需要登录；非管理员只能看到公开配置
     */
    @LogAnnotation
    @PostMapping("/list")
    @SuppressWarnings("unchecked")
    public R getConfigs() {
        R result = viteConfigService.getConfigs();
        if (result.getCode() != 0 || !(result.getData() instanceof Map)) return result;
        return R.ok(visibleConfigs((Map<String, String>) result.getData(), currentUserIsAdmin()));
    }

    /**
     * 根据配置名获取配置值
     * 无需登录即可访问，因此只返回公开配置
     */
    @LogAnnotation
    @PostMapping("/get")
    public R getConfigByName(@RequestBody Map<String, Object> params) {
        Object name = params == null ? null : params.get("name");
        if (name == null || !isPublicConfigName(name.toString())) {
            return R.err("配置不存在");
        }
        return viteConfigService.getConfigByName(name.toString());
    }

    private boolean currentUserIsAdmin() {
        try {
            return Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 批量更新网站配置
     * 需要管理员权限
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R updateConfigs(@RequestBody Map<String, String> configMap) {
        return viteConfigService.updateConfigs(configMap);
    }

    /**
     * 更新单个配置项
     * 需要管理员权限
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/update-single")
    public R updateConfig(@RequestBody Map<String, Object> params) {
        String name = params.get("name").toString();
        String value = params.get("value").toString();
        return viteConfigService.updateConfig(name, value);
    }

}
