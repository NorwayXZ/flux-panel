package com.admin.entity;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.TableField;
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
public class Node extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private Integer ownerUserId;

    private String secret;

    private String ip;

    private String serverIp;

    private String version;

    private Integer portSta;

    private Integer portEnd;

    private Integer http;

    private Integer tls;

    private Integer socks;

    @TableField(exist = false)
    private String ownerUserName;

    @TableField(exist = false)
    private Integer ownerRoleId;

    @TableField(exist = false)
    private String accessType;

    @TableField(exist = false)
    private Boolean editable;

    @TableField(exist = false)
    private Boolean deletable;

    @TableField(exist = false)
    private Integer portPoolGroupSize;

}
