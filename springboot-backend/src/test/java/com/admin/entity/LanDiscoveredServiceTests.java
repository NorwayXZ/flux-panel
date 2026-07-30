package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LanDiscoveredServiceTests {
    @Test
    void escapesSensitiveColumnForMySql8() throws NoSuchFieldException {
        TableField mapping = LanDiscoveredService.class.getDeclaredField("sensitive").getAnnotation(TableField.class);

        assertNotNull(mapping);
        assertEquals("`sensitive`", mapping.value());
    }
}
