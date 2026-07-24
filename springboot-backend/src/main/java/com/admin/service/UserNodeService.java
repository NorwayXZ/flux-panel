package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.UserNode;
import com.baomidou.mybatisplus.extension.service.IService;

public interface UserNodeService extends IService<UserNode> {
    R assign(Integer userId, Integer nodeId);

    R listByUser(Integer userId);

    R removeAccess(Integer userId, Integer nodeId);
}
