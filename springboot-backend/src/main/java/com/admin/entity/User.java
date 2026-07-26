package com.admin.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 *
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class User extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 创建时间（时间戳）
     */
    private Long createdTime;

    /**
     * 更新时间（时间戳）
     */
    private Long updatedTime;

    /**
     * 状态（0：正常，1：删除）
     */
    private Integer status;

    private String user;

    private String pwd;

    private Integer roleId;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long expTime;

    private Long flow;

    private Integer flowUnlimited;

    private Long inFlow;

    private Long outFlow;

    private Long ownedInFlow;

    private Long ownedOutFlow;

    private Integer num;

    private Integer forwardUnlimited;

    private Long flowResetTime;

    @TableField(exist = false)
    private Long totalFlow;

    @TableField(exist = false)
    private Boolean totalFlowUnlimited;

    @TableField(exist = false)
    private Long totalUsedFlow;

    @TableField(exist = false)
    private Integer totalNum;

    @TableField(exist = false)
    private Boolean totalNumUnlimited;

    @TableField(exist = false)
    private Integer tunnelPermissionCount;

    @TableField(exist = false)
    private Integer nodePermissionCount;

    @TableField(exist = false)
    private Integer portPermissionCount;


}
