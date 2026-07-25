package com.admin.service;

import com.admin.common.dto.InternalConnectorCreateDto;
import com.admin.common.dto.PortPoolCreateDto;
import com.admin.common.dto.PublishedServiceCreateDto;
import com.admin.common.lang.R;

public interface ServicePublishingService {
    R createConnector(InternalConnectorCreateDto dto);
    R listConnectors();
    R connectorInstallCommand(Long id);
    R deleteConnector(Long id);
    R createPortPool(PortPoolCreateDto dto);
    R listPortPools();
    R deletePortPool(Long id);
    R createPublishedService(PublishedServiceCreateDto dto);
    R listPublishedServices();
    R renewPublishedService(Long id, Integer hours);
    R deletePublishedService(Long id);
    R listLeaseEvents(Long serviceId);
    void processLeaseLifecycle();
}
