package com.zalobg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "zalo_message", autoResultMap = true)
public class ZaloMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String mongoId;
    private Long msgId;
    private Long ownerZaloId;
    private String ownerAccount;
    private Long peerUserId;
    private Long groupId;
    private String content;
    private Integer msgType;
    private Integer direction;
    private Integer isSend;
    private Integer isRead;
    private Integer seen;
    private String nickname;
    private String dst;
    @TableField("describe_")
    private String describe;
    private Long bindId;
    private Long deptId;
    private LocalDateTime gmtCreate;
    private LocalDateTime collectedAt;
    private String rawJson;
    @TableLogic
    private Integer deleted;
}
