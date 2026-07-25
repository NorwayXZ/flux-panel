package com.admin.service;

import com.admin.entity.LayoutPreference;
import com.admin.mapper.LayoutPreferenceMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LayoutPreferenceServiceTests {

    @Mock
    private LayoutPreferenceMapper layoutPreferenceMapper;

    @InjectMocks
    private LayoutPreferenceService layoutPreferenceService;

    @Test
    void savesUniqueItemsInRequestedOrder() {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        List<String> result = layoutPreferenceService.saveOrder(
                7,
                "node-cards",
                List.of("12", "8", "12", "3")
        );

        assertEquals(List.of("12", "8", "3"), result);
        verify(layoutPreferenceMapper).upsert(eq(7), eq("node-cards"), json.capture(), any(Long.class));
        assertEquals("[\"12\",\"8\",\"3\"]", json.getValue());
    }

    @Test
    void returnsSavedOrder() {
        LayoutPreference preference = new LayoutPreference();
        preference.setItemOrder("[\"total-flow\",\"used-flow\"]");
        when(layoutPreferenceMapper.selectOne(any(Wrapper.class))).thenReturn(preference);

        assertEquals(
                List.of("total-flow", "used-flow"),
                layoutPreferenceService.getOrder(1, "dashboard-summary-cards")
        );
    }

    @Test
    void rejectsUnsafeScopeAndItemKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> layoutPreferenceService.getOrder(1, "../users")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> layoutPreferenceService.saveOrder(1, "node-cards", List.of("1 OR 1=1"))
        );
    }
}
