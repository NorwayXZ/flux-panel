package com.admin.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class UserTunnel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private Integer userId;

    private Integer tunnelId;

    private Long flow;

    private Integer flowUnlimited;

    private Long inFlow;

    private Long outFlow;

    private Long flowResetTime;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long expTime;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Integer speedId;

    private Integer num;

    private Integer forwardUnlimited;

    private Integer status;

}
