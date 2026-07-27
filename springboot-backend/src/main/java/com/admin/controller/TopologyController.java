package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.service.TopologyService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/topology")
public class TopologyController {
    private final TopologyService service;

    public TopologyController(TopologyService service) {
        this.service = service;
    }

    @PostMapping("/graph")
    public R graph() {
        return service.graph();
    }
}
