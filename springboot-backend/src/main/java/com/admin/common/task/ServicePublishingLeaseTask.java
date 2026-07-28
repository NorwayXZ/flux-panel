package com.admin.common.task;

import com.admin.service.ServicePublishingService;
import com.admin.service.HomeProxyService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ServicePublishingLeaseTask {
    private final ServicePublishingService service;
    private final HomeProxyService homeProxyService;

    public ServicePublishingLeaseTask(ServicePublishingService service, HomeProxyService homeProxyService) {
        this.service = service;
        this.homeProxyService = homeProxyService;
    }

    @Scheduled(fixedDelayString = "${service-publishing.lease-check-interval-ms:30000}")
    public void processLeases() {
        service.processLeaseLifecycle();
        homeProxyService.processPendingDeletes();
        homeProxyService.refreshDirectIpv6Addresses();
    }
}
