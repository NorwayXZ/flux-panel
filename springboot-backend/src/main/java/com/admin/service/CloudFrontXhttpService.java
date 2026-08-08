package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.AllowedMethods;
import software.amazon.awssdk.services.cloudfront.model.CachedMethods;
import software.amazon.awssdk.services.cloudfront.model.CookiePreference;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionRequest;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionResponse;
import software.amazon.awssdk.services.cloudfront.model.CustomOriginConfig;
import software.amazon.awssdk.services.cloudfront.model.DefaultCacheBehavior;
import software.amazon.awssdk.services.cloudfront.model.Distribution;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.DeleteDistributionRequest;
import software.amazon.awssdk.services.cloudfront.model.ForwardedValues;
import software.amazon.awssdk.services.cloudfront.model.GeoRestriction;
import software.amazon.awssdk.services.cloudfront.model.GeoRestrictionType;
import software.amazon.awssdk.services.cloudfront.model.Method;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.OriginProtocolPolicy;
import software.amazon.awssdk.services.cloudfront.model.Origins;
import software.amazon.awssdk.services.cloudfront.model.PriceClass;
import software.amazon.awssdk.services.cloudfront.model.Restrictions;
import software.amazon.awssdk.services.cloudfront.model.ViewerCertificate;
import software.amazon.awssdk.services.cloudfront.model.ViewerProtocolPolicy;

import java.util.Map;
import java.util.UUID;

@Service
public class CloudFrontXhttpService {
    private final JdbcTemplate jdbcTemplate;
    private final AwsAccessService awsAccessService;
    private final DnsProviderService dnsProviderService;
    private final NodeMapper nodeMapper;

    public CloudFrontXhttpService(JdbcTemplate jdbcTemplate, AwsAccessService awsAccessService,
                                  DnsProviderService dnsProviderService, NodeMapper nodeMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.awsAccessService = awsAccessService;
        this.dnsProviderService = dnsProviderService;
        this.nodeMapper = nodeMapper;
    }

    public ProvisionedPair provision(Long appId, Long accountId, Long zoneId, String originDomain,
                                     Long entryNodeId, Integer originPort) {
        AwsAccessService.AwsAccount account = awsAccessService.load(accountId);
        if (account == null) throw new IllegalArgumentException("AWS 账号不存在");
        Node entry = nodeMapper.selectById(entryNodeId);
        if (entry == null) throw new IllegalArgumentException("入口节点不存在");
        String entryHost = StringUtils.defaultIfBlank(entry.getServerIp(), entry.getIp());
        String origin = dnsProviderService.normalizeDomain(zoneId, originDomain);
        String recordId = dnsProviderService.ensureXhttpOriginRecord(zoneId, origin, entryHost, appId);
        String uploadId = null;
        try (CloudFrontClient client = client(account)) {
            Distribution upload = create(client, appId, "upload", origin, originPort);
            uploadId = upload.id();
            Distribution download = create(client, appId, "download", origin, originPort);
            return new ProvisionedPair(origin, recordId, upload.id(), upload.domainName(), download.id(), download.domainName());
        } catch (RuntimeException e) {
            if (uploadId != null) disableAsync(accountId, uploadId);
            try { dnsProviderService.deleteXhttpOriginRecord(appId); } catch (RuntimeException ignored) { }
            throw new IllegalStateException("CloudFront 自动创建失败：" + concise(e.getMessage()), e);
        }
    }

    public R cleanup(Long appId) {
        Map<String, Object> app = one(appId);
        if (app == null) return R.ok();
        Long accountId = number(app.get("awsAccessAccountId"));
        if (accountId == null) return R.ok();
        AwsAccessService.AwsAccount account = awsAccessService.load(accountId);
        if (account == null) return R.err("AWS 账号已被删除，无法清理 CloudFront");
        String uploadId = string(app.get("uploadDistributionId"));
        String downloadId = string(app.get("downloadDistributionId"));
        try (CloudFrontClient client = client(account)) {
            boolean uploadDeleted = StringUtils.isBlank(uploadId) || disableOrDelete(client, uploadId);
            boolean downloadDeleted = StringUtils.isBlank(downloadId) || disableOrDelete(client, downloadId);
            if (!uploadDeleted || !downloadDeleted) {
                jdbcTemplate.update("UPDATE network_route_application SET cloudfront_state='disabling',updated_time=? WHERE id=?", System.currentTimeMillis(), appId);
                return R.err("CloudFront 已提交停用，AWS 通常需要数分钟完成；完成后再次点击删除即可最终回收");
            }
            dnsProviderService.deleteXhttpOriginRecord(appId);
            jdbcTemplate.update("UPDATE network_route_application SET cloudfront_state='deleted',updated_time=? WHERE id=?", System.currentTimeMillis(), appId);
            return R.ok();
        } catch (RuntimeException e) {
            return R.err("CloudFront 停用失败：" + concise(e.getMessage()));
        }
    }

    private Distribution create(CloudFrontClient client, Long appId, String role, String originDomain, int originPort) {
        String originId = "cloudnest-" + appId + "-" + role;
        Origin origin = Origin.builder().id(originId).domainName(originDomain)
                .customOriginConfig(CustomOriginConfig.builder().httpPort(originPort).httpsPort(443)
                        .originProtocolPolicy(OriginProtocolPolicy.HTTP_ONLY).originReadTimeout(60).originKeepaliveTimeout(60).build())
                .connectionAttempts(3).connectionTimeout(10).build();
        ForwardedValues forwarded = ForwardedValues.builder().queryString(true)
                .headers(h -> h.quantity(1).items("*"))
                .cookies(CookiePreference.builder().forward("all").build()).build();
        DefaultCacheBehavior behavior = DefaultCacheBehavior.builder().targetOriginId(originId)
                .viewerProtocolPolicy(ViewerProtocolPolicy.HTTPS_ONLY)
                .allowedMethods(AllowedMethods.builder().quantity(7).items(Method.GET, Method.HEAD, Method.OPTIONS,
                        Method.PUT, Method.PATCH, Method.POST, Method.DELETE)
                        .cachedMethods(CachedMethods.builder().quantity(2).items(Method.GET, Method.HEAD).build()).build())
                .forwardedValues(forwarded).minTTL(0L).defaultTTL(0L).maxTTL(0L).compress(false).build();
        DistributionConfig config = DistributionConfig.builder()
                .callerReference("cloudnest-" + appId + "-" + role + "-" + UUID.randomUUID())
                .comment("CloudNest XHTTP " + role + " application " + appId)
                .origins(Origins.builder().quantity(1).items(origin).build()).defaultCacheBehavior(behavior)
                .viewerCertificate(ViewerCertificate.builder().cloudFrontDefaultCertificate(true).build())
                .restrictions(Restrictions.builder().geoRestriction(GeoRestriction.builder()
                        .restrictionType(GeoRestrictionType.NONE).quantity(0).build()).build())
                .priceClass(PriceClass.PRICE_CLASS_ALL).httpVersion("http2and3").isIPV6Enabled(true).enabled(true).build();
        CreateDistributionResponse response = client.createDistribution(CreateDistributionRequest.builder().distributionConfig(config).build());
        if (response.distribution() == null || StringUtils.isAnyBlank(response.distribution().id(), response.distribution().domainName())) {
            throw new IllegalStateException("AWS 未返回 Distribution ID 或域名");
        }
        return response.distribution();
    }

    private void disable(CloudFrontClient client, String id) {
        var current = client.getDistributionConfig(request -> request.id(id));
        if (!Boolean.TRUE.equals(current.distributionConfig().enabled())) return;
        client.updateDistribution(request -> request.id(id).ifMatch(current.eTag())
                .distributionConfig(current.distributionConfig().toBuilder().enabled(false).build()));
    }

    private boolean disableOrDelete(CloudFrontClient client, String id) {
        var current = client.getDistributionConfig(request -> request.id(id));
        if (Boolean.TRUE.equals(current.distributionConfig().enabled())) {
            client.updateDistribution(request -> request.id(id).ifMatch(current.eTag())
                    .distributionConfig(current.distributionConfig().toBuilder().enabled(false).build()));
            return false;
        }
        client.deleteDistribution(DeleteDistributionRequest.builder().id(id).ifMatch(current.eTag()).build());
        return true;
    }

    private void disableAsync(Long accountId, String distributionId) {
        try {
            AwsAccessService.AwsAccount account = awsAccessService.load(accountId);
            if (account == null) return;
            try (CloudFrontClient client = client(account)) { disable(client, distributionId); }
        } catch (RuntimeException ignored) { }
    }

    private CloudFrontClient client(AwsAccessService.AwsAccount account) {
        return CloudFrontClient.builder().credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(account.accessKeyId(), account.secretAccessKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private Map<String, Object> one(Long id) {
        var rows = jdbcTemplate.queryForList("SELECT aws_access_account_id AS awsAccessAccountId,xhttp_upload_distribution_id AS uploadDistributionId,"
                + "xhttp_download_distribution_id AS downloadDistributionId FROM network_route_application WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : value == null ? null : Long.valueOf(value.toString()); }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private static String concise(String value) { if (value == null) return "未知错误"; return value.length() > 300 ? value.substring(0, 300) : value; }

    public record ProvisionedPair(String originDomain, String dnsRecordId, String uploadDistributionId,
                                  String uploadDomain, String downloadDistributionId, String downloadDomain) { }
}
