package com.admin.service;

import com.admin.entity.LayoutPreference;
import com.admin.mapper.LayoutPreferenceMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class LayoutPreferenceService {

    private static final Pattern SAFE_KEY = Pattern.compile("^[a-z0-9][a-z0-9:_-]{0,63}$");
    private static final int MAX_ITEMS = 500;

    @Resource
    private LayoutPreferenceMapper layoutPreferenceMapper;

    public List<String> getOrder(Integer userId, String scope) {
        validateScope(scope);

        QueryWrapper<LayoutPreference> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("scope", scope).last("LIMIT 1");
        LayoutPreference preference = layoutPreferenceMapper.selectOne(query);
        if (preference == null || preference.getItemOrder() == null) {
            return List.of();
        }

        try {
            List<String> savedOrder = JSON.parseArray(preference.getItemOrder(), String.class);
            return savedOrder == null ? List.of() : sanitizeOrder(savedOrder);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public List<String> saveOrder(Integer userId, String scope, List<?> rawOrder) {
        validateScope(scope);
        List<String> order = sanitizeOrder(rawOrder);
        layoutPreferenceMapper.upsert(
                userId,
                scope,
                JSON.toJSONString(order),
                System.currentTimeMillis()
        );
        return order;
    }

    private void validateScope(String scope) {
        if (scope == null || !SAFE_KEY.matcher(scope).matches()) {
            throw new IllegalArgumentException("布局区域无效");
        }
    }

    private List<String> sanitizeOrder(List<?> rawOrder) {
        if (rawOrder == null) {
            throw new IllegalArgumentException("卡片顺序不能为空");
        }
        if (rawOrder.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("卡片数量超过限制");
        }

        Set<String> uniqueItems = new LinkedHashSet<>();
        for (Object item : rawOrder) {
            String value = item == null ? "" : item.toString();
            if (!SAFE_KEY.matcher(value).matches()) {
                throw new IllegalArgumentException("卡片标识无效");
            }
            uniqueItems.add(value);
        }
        return new ArrayList<>(uniqueItems);
    }
}
