package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.ClientIpUtil;
import com.admin.service.TelegramNotificationService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/user")
public class UserController extends BaseController {

    @Resource
    private TelegramNotificationService telegramNotificationService;

    @LogAnnotation
    @PostMapping("/login")
    public R login(@Validated @RequestBody LoginDto loginDto, HttpServletRequest request) {
        R result = userService.login(loginDto);
        if (result.getCode() == 0) {
            telegramNotificationService.notifyLoginOutsideWhitelist(
                    loginDto.getUsername(), ClientIpUtil.resolve(request), System.currentTimeMillis());
        }
        return result;
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody UserDto userDto) {
        return userService.createUser(userDto);
    }


    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R readAll() {
        return userService.getAllUsers();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody UserUpdateDto userUpdateDto) {
        return userService.updateUser(userUpdateDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return userService.deleteUser(id);
    }

    @LogAnnotation
    @PostMapping("/package")
    public R getUserPackageInfo() {
        return userService.getUserPackageInfo();
    }

    @LogAnnotation
    @PostMapping("/updatePassword")
    public R updatePassword(@Validated @RequestBody ChangePasswordDto changePasswordDto) {
        return userService.updatePassword(changePasswordDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/reset")
    public R reset(@Validated @RequestBody ResetFlowDto resetFlowDto) {
        return userService.reset(resetFlowDto);
    }

}
