package com.zalobg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "zalo_friend", autoResultMap = true)
public class ZaloFriend {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String mongoId;
    private Long ownerZaloId;
    private String ownerAccount;
    private Long friendUserId;
    private Long friendUuid;
    private Integer fstatus;
    private String remark;
    private String displayName;
    private String userName;
    private String avatar;
    private String cover;
    private Integer gender;
    private String phone;
    private String statusText;
    private Integer actionCount;
    private Long deptId;
    private Integer msgSum;
    private LocalDateTime latestMsgTime;
    private LocalDateTime gmtCreate;
    private LocalDateTime collectedAt;
    private String rawJson;
    @TableLogic
    private Integer deleted;
}
