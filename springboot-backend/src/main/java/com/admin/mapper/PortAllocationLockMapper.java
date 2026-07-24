package com.admin.mapper;

import org.apache.ibatis.annotations.Select;

public interface PortAllocationLockMapper {

    @Select("SELECT id FROM port_allocation_lock WHERE id = 1 FOR UPDATE")
    Integer lockForUpdate();
}
