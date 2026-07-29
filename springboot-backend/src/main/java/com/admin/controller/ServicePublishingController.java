package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.InternalConnectorCreateDto;
import com.admin.common.dto.DomainRouteCreateDto;
import com.admin.common.dto.PortPoolCreateDto;
import com.admin.common.dto.PublishedServiceCreateDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.common.lang.R;
import com.admin.service.HomeProxyService;
import com.admin.service.NatTraversalService;
import com.admin.service.ServicePublishingService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/service-publishing")
public class ServicePublishingController {
    @Resource
    private ServicePublishingService service;

    @Resource
    private HomeProxyService homeProxyService;

    @Resource
    private NatTraversalService natTraversalService;

    @LogAnnotation @PostMapping("/home-proxy/create")
    public R createHomeProxy(@Validated @RequestBody com.admin.common.dto.HomeProxyRouteCreateDto dto) {
        return homeProxyService.create(dto);
    }

    @LogAnnotation @PostMapping("/home-proxy/list")
    public R listHomeProxy() {
        return homeProxyService.list();
    }

    @LogAnnotation @PostMapping("/home-proxy/refresh-ipv6")
    public R refreshHomeProxyIpv6(@RequestBody Map<String, Object> params) {
        return homeProxyService.refreshIpv6(Long.valueOf(params.get("id").toString()));
    }

    @LogAnnotation @PostMapping("/home-proxy/delete")
    public R deleteHomeProxy(@RequestBody Map<String, Object> params) {
        return homeProxyService.delete(Long.valueOf(params.get("id").toString()));
    }

    @LogAnnotation @PostMapping("/home-proxy/nat/retry")
    public R retryHomeProxyNat(@RequestBody Map<String, Object> params) {
        return natTraversalService.retry(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/home-proxy/nat/events")
    public R listHomeProxyNatEvents(@RequestBody Map<String, Object> params) {
        return natTraversalService.events(Long.valueOf(params.get("id").toString()));
    }

    @LogAnnotation @PostMapping("/connector/create")
    public R createConnector(@Validated @RequestBody InternalConnectorCreateDto dto) {
        return service.createConnector(dto);
    }

    @LogAnnotation @PostMapping("/connector/list")
    public R listConnectors() {
        return service.listConnectors();
    }

    @LogAnnotation @PostMapping("/connector/install")
    public R connectorInstall(@RequestBody Map<String, Object> params) {
        Object platform = params.get("platform");
        Object action = params.get("action");
        return service.connectorInstallCommand(
                Long.valueOf(params.get("id").toString()),
                platform == null ? null : platform.toString(),
                action != null && "uninstall".equalsIgnoreCase(action.toString()));
    }

    @LogAnnotation @PostMapping("/connector/delete")
    public R deleteConnector(@RequestBody Map<String, Object> params) {
        return service.deleteConnector(Long.valueOf(params.get("id").toString()));
    }

    @LogAnnotation @RequireRole @PostMapping("/pool/create")
    public R createPool(@Validated @RequestBody PortPoolCreateDto dto) {
        return service.createPortPool(dto);
    }

    @LogAnnotation @PostMapping("/pool/list")
    public R listPools() {
        return service.listPortPools();
    }

    @LogAnnotation @PostMapping("/grant/list")
    public R listGrants(@RequestBody(required = false) Map<String, Object> params) {
        Integer userId = params == null || params.get("userId") == null ? null : Integer.valueOf(params.get("userId").toString());
        return service.listPortGrants(userId);
    }

    @PostMapping("/ledger/list")
    @RequireRole
    public R listLedger(@RequestBody(required = false) PortLedgerQueryDto query) {
        return service.listPortLedger(query == null ? new PortLedgerQueryDto() : query);
    }

    @PostMapping("/ledger/diagnose")
    @RequireRole
    public R diagnosePort(@RequestBody PortLedgerQueryDto query) {
        return service.diagnosePort(query.getNodeId(), query.getPort());
    }

    @LogAnnotation @RequireRole @PostMapping("/pool/delete")
    public R deletePool(@RequestBody Map<String, Object> params) {
        return service.deletePortPool(Long.valueOf(params.get("id").toString()));
    }

    @LogAnnotation @PostMapping("/service/create")
    public R createService(@Validated @RequestBody PublishedServiceCreateDto dto) {
        return service.createPublishedService(dto);
    }

    @LogAnnotation @PostMapping("/service/list")
    public R listServices() {
        return service.listPublishedServices();
    }

    @LogAnnotation @PostMapping("/service/renew")
    public R renewService(@RequestBody Map<String, Object> params) {
        return service.renewPublishedService(
                Long.valueOf(params.get("id").toString()),
                params.get("hours") == null ? null : Integer.valueOf(params.get("hours").toString()),
                Boolean.parseBoolean(String.valueOf(params.getOrDefault("permanent", false))));
    }

    @LogAnnotation @PostMapping("/service/delete")
    public R deleteService(@RequestBody Map<String, Object> params) {
        return service.deletePublishedService(Long.valueOf(params.get("id").toString()));
    }

    @LogAnnotation @PostMapping("/domain/create")
    public R createDomainRoute(@Validated @RequestBody DomainRouteCreateDto dto) {
        return service.createDomainRoute(dto);
    }

    @LogAnnotation @PostMapping("/domain/list")
    public R listDomainRoutes() {
        return service.listDomainRoutes();
    }

    @LogAnnotation @PostMapping("/domain/delete")
    public R deleteDomainRoute(@RequestBody Map<String, Object> params) {
        return service.deleteDomainRoute(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/certificate/list")
    @RequireRole
    public R listManagedCertificates() {
        return service.listManagedCertificates();
    }

    @LogAnnotation @PostMapping("/certificate/retry")
    @RequireRole
    public R retryManagedCertificate(@RequestBody Map<String, Object> params) {
        return service.retryManagedCertificate(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/service/events")
    public R events(@RequestBody Map<String, Object> params) {
        return service.listLeaseEvents(Long.valueOf(params.get("id").toString()));
    }
}
