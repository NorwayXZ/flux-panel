package com.admin.common.task;

import com.admin.common.dto.ConfigItem;
import com.admin.common.dto.GostConfigDto;
import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.admin.mapper.PrivateProxyMapper;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckGostConfigAsyncTests {
    @Mock private NodeService nodeService;
    @Mock private SpeedLimitService speedLimitService;
    @Mock private PrivateProxyMapper privateProxyMapper;

    private CheckGostConfigAsync task;

    @BeforeEach
    void setUp() {
        task = new CheckGostConfigAsync();
        ReflectionTestUtils.setField(task, "nodeService", nodeService);
        ReflectionTestUtils.setField(task, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(task, "privateProxyMapper", privateProxyMapper);
    }

    @Test
    void keepsValidNamedLimiterWithoutTreatingItAsNumericPreset() {
        Node node = new Node();
        node.setId(8L);
        PrivateProxy proxy = new PrivateProxy();
        proxy.setId(42L);
        proxy.setNodeId(8L);
        proxy.setGrantedByUserId(1);
        proxy.setState("active");
        ConfigItem limiter = new ConfigItem();
        limiter.setName("private-proxy-user-42");
        GostConfigDto config = new GostConfigDto();
        config.setLimiters(List.of(limiter));
        when(nodeService.getById("8")).thenReturn(node);
        when(privateProxyMapper.selectById(42L)).thenReturn(proxy);

        task.cleanNodeConfigs("8", config);

        verify(privateProxyMapper).selectById(42L);
        verify(speedLimitService, never()).getById("private-proxy-user-42");
    }
}
