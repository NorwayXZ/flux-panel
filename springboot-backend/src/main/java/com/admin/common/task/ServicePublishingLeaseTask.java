package com.admin.common.task;

import com.admin.service.ServicePublishingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ServicePublishingLeaseTask {
    private final ServicePublishingService service;

    public ServicePublishingLeaseTask(ServicePublishingService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${service-publishing.lease-check-interval-ms:30000}")
    public void processLeases() {
        service.processLeaseLifecycle();
    }
}
