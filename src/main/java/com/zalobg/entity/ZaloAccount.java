package com.zalobg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "zalo_account", autoResultMap = true)
public class ZaloAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long zaloId;
    private String account;
    private String nickName;
    private String region;
    private String password;
    private Integer sex;
    private String headimg;
    private Integer online;
    private Integer accountStatus;
    private String remark;
    private String proxyIp;
    private Long proxyId;
    private Integer friendsSum;
    private Integer groupSum;
    private Integer msgSum;
    private Long groupId;
    private String groupName;
    private Long deptId;
    private Long bindId;
    private String sdbo;
    private String wxid;
    private Integer isShop;
    private Integer realName;
    private String createBy;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtUpdate;
    private LocalDateTime collectedAt;
    private String rawJson;
    @TableLogic
    private Integer deleted;
}
