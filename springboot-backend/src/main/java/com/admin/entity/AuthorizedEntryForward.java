package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

@Data
public class AuthorizedEntryForward {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long portId;
    private Long forwardId;
    private Long nodeId;
    private Long createdTime;
    private Long updatedTime;
}
