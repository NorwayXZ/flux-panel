package com.admin.mapper;

import com.admin.entity.LayoutPreference;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface LayoutPreferenceMapper extends BaseMapper<LayoutPreference> {

    @Insert("INSERT INTO layout_preference (user_id, scope, item_order, updated_time) "
            + "VALUES (#{userId}, #{scope}, #{itemOrder}, #{updatedTime}) "
            + "ON DUPLICATE KEY UPDATE item_order = VALUES(item_order), updated_time = VALUES(updated_time)")
    int upsert(@Param("userId") Integer userId,
               @Param("scope") String scope,
               @Param("itemOrder") String itemOrder,
               @Param("updatedTime") Long updatedTime);
}
