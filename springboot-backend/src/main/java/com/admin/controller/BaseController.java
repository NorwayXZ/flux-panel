package com.admin.controller;

import com.admin.service.*;
import org.springframework.beans.factory.annotation.Autowired;

public class BaseController {

    @Autowired
    UserService userService;

    @Autowired
    NodeService nodeService;

    @Autowired
    UserTunnelService userTunnelService;

    @Autowired
    UserNodeService userNodeService;

    @Autowired
    TunnelService tunnelService;

    @Autowired
    ForwardService forwardService;

    @Autowired
    ViteConfigService viteConfigService;

}
