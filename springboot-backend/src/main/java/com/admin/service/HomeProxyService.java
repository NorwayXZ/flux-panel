package com.admin.service;

import com.admin.common.dto.HomeProxyRouteCreateDto;
import com.admin.common.lang.R;

public interface HomeProxyService {
    R create(HomeProxyRouteCreateDto dto);
    R list();
    R delete(Long id);
    void processPendingDeletes();
}
