package com.admin.service;

import com.admin.common.dto.InternalConnectorCreateDto;
import com.admin.common.dto.DomainRouteCreateDto;
import com.admin.common.dto.PortPoolCreateDto;
import com.admin.common.dto.PublishedServiceCreateDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.common.lang.R;

public interface ServicePublishingService {
    R createConnector(InternalConnectorCreateDto dto);
    R listConnectors();
    R connectorInstallCommand(Long id, String platform, boolean uninstall);
    R deleteConnector(Long id);
    R createPortPool(PortPoolCreateDto dto);
    R listPortPools();
    R listPortGrants(Integer userId);
    R deletePortPool(Long id);
    R createPublishedService(PublishedServiceCreateDto dto);
    R listPublishedServices();
    R renewPublishedService(Long id, Integer hours, boolean permanent);
    R deletePublishedService(Long id);
    R createDomainRoute(DomainRouteCreateDto dto);
    R listDomainRoutes();
    R deleteDomainRoute(Long id);
    R listLeaseEvents(Long serviceId);
    R listPortLedger(PortLedgerQueryDto query);
    R diagnosePort(Long nodeId, Integer port);
    void processLeaseLifecycle();
}
