package com.admin.entity;

import com.admin.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-08-14
 */
@Data
public class StatisticsFlow  {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;

    private Long flow;

    /** Inbound and outbound traffic in this hour; 0 for samples taken before 2.73.0. */
    private Long inFlow;

    private Long outFlow;

    private Long totalFlow;

    /** Running totals per direction; null for samples taken before 2.73.0. */
    private Long totalInFlow;

    private Long totalOutFlow;

    private String time;

    private Long createdTime;


}
