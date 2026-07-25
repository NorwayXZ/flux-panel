package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserNode implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private Integer userId;

    private Integer nodeId;

    private Long createdTime;

    private Long flow;

    private Long inFlow;

    private Long outFlow;

    private Integer flowUnlimited;

    private Integer num;

    private Integer forwardUnlimited;

    private Long flowResetTime;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long expTime;

    private Integer status;
}
