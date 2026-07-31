package com.admin.service.impl;

import com.admin.common.dto.UserTunnelProvisionDto;
import com.admin.entity.Forward;
import com.admin.entity.UserTunnel;
import com.admin.mapper.UserNodeMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.ForwardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTests {
    @Mock private UserTunnelMapper userTunnelMapper;
    @Mock private UserNodeMapper userNodeMapper;
    @Mock private ForwardService forwardService;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userTunnelMapper", userTunnelMapper);
        ReflectionTestUtils.setField(service, "userNodeMapper", userNodeMapper);
        ReflectionTestUtils.setField(service, "forwardService", forwardService);
    }

    @Test
    void refreshesOnlyExistingUserForwardsWhenTunnelLimiterChanges() {
        UserTunnel existing = permission(7, 21, 3);
        Forward forward = new Forward();
        forward.setId(99L);
        when(userTunnelMapper.selectList(any())).thenReturn(List.of(existing));
        when(forwardService.list(any())).thenReturn(List.of(forward));

        ReflectionTestUtils.invokeMethod(service, "syncPermissions", 7,
                List.of(provision(21, 4)), null);

        verify(userTunnelMapper).updateById(existing);
        verify(forwardService).updateForwardA(forward);
    }

    @Test
    void doesNotReloadForwardsWhenTunnelLimiterIsUnchanged() {
        UserTunnel existing = permission(7, 21, 3);
        when(userTunnelMapper.selectList(any())).thenReturn(List.of(existing));

        ReflectionTestUtils.invokeMethod(service, "syncPermissions", 7,
                List.of(provision(21, 3)), null);

        verify(userTunnelMapper).updateById(existing);
        verify(forwardService, never()).list(any());
        verify(forwardService, never()).updateForwardA(any());
    }

    private UserTunnel permission(int userId, int tunnelId, int speedId) {
        UserTunnel permission = new UserTunnel();
        permission.setId(10);
        permission.setUserId(userId);
        permission.setTunnelId(tunnelId);
        permission.setSpeedId(speedId);
        return permission;
    }

    private UserTunnelProvisionDto provision(int tunnelId, int speedId) {
        UserTunnelProvisionDto dto = new UserTunnelProvisionDto();
        dto.setTunnelId(tunnelId);
        dto.setFlow(100L);
        dto.setFlowUnlimited(false);
        dto.setNum(10);
        dto.setForwardUnlimited(false);
        dto.setFlowResetTime(0L);
        dto.setSpeedId(speedId);
        dto.setStatus(1);
        return dto;
    }
}
