package com.zalobg.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zalobg.common.R;
import com.zalobg.entity.ZaloAccount;
import com.zalobg.entity.ZaloFriend;
import com.zalobg.entity.ZaloMessage;
import com.zalobg.mapper.ZaloAccountMapper;
import com.zalobg.mapper.ZaloFriendMapper;
import com.zalobg.mapper.ZaloMessageMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "管理后台查询 (Query)", description = "只读查询接口, 均需 JWT")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminQueryController {

    private final ZaloAccountMapper accountMapper;
    private final ZaloFriendMapper friendMapper;
    private final ZaloMessageMapper messageMapper;

    // ---------- 账号 ----------

    @Operation(summary = "分页查询 Zalo 账号")
    @GetMapping("/accounts")
    public R<IPage<ZaloAccount>> listAccounts(
            @Parameter(description = "页码, 从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "Zalo 账号 ID (精确)") @RequestParam(required = false) Long zaloId,
            @Parameter(description = "账号手机号 (模糊)") @RequestParam(required = false) String account,
            @Parameter(description = "昵称 (模糊)") @RequestParam(required = false) String nickName,
            @Parameter(description = "部门 ID") @RequestParam(required = false) Long deptId,
            @Parameter(description = "在线状态: 1=在线 2=离线") @RequestParam(required = false) Integer online,
            @Parameter(description = "账号状态") @RequestParam(required = false) Integer accountStatus
    ) {
        LambdaQueryWrapper<ZaloAccount> w = new LambdaQueryWrapper<>();
        if (zaloId != null) w.eq(ZaloAccount::getZaloId, zaloId);
        if (account != null && !account.isEmpty()) w.like(ZaloAccount::getAccount, account);
        if (nickName != null && !nickName.isEmpty()) w.like(ZaloAccount::getNickName, nickName);
        if (deptId != null) w.eq(ZaloAccount::getDeptId, deptId);
        if (online != null) w.eq(ZaloAccount::getOnline, online);
        if (accountStatus != null) w.eq(ZaloAccount::getAccountStatus, accountStatus);
        w.orderByDesc(ZaloAccount::getCollectedAt);
        IPage<ZaloAccount> p = accountMapper.selectPage(Page.of(page, size), w);
        return R.ok(p);
    }

    @Operation(summary = "账号详情")
    @GetMapping("/accounts/{id}")
    public R<ZaloAccount> accountDetail(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return R.ok(accountMapper.selectById(id));
    }

    // ---------- 好友 ----------

    @Operation(summary = "分页查询好友列表 (可按账号过滤)")
    @GetMapping("/friends")
    public R<IPage<ZaloFriend>> listFriends(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "归属账号 (zalo_account.zalo_id)") @RequestParam(required = false) Long ownerZaloId,
            @Parameter(description = "好友 userId (精确)") @RequestParam(required = false) Long friendUserId,
            @Parameter(description = "好友显示名 (模糊)") @RequestParam(required = false) String displayName,
            @Parameter(description = "好友手机号 (模糊)") @RequestParam(required = false) String phone,
            @Parameter(description = "关系: 0=陌生人 3=好友 5=非好友") @RequestParam(required = false) Integer fstatus,
            @Parameter(description = "部门 ID") @RequestParam(required = false) Long deptId
    ) {
        LambdaQueryWrapper<ZaloFriend> w = new LambdaQueryWrapper<>();
        if (ownerZaloId != null) w.eq(ZaloFriend::getOwnerZaloId, ownerZaloId);
        if (friendUserId != null) w.eq(ZaloFriend::getFriendUserId, friendUserId);
        if (displayName != null && !displayName.isEmpty()) w.like(ZaloFriend::getDisplayName, displayName);
        if (phone != null && !phone.isEmpty()) w.like(ZaloFriend::getPhone, phone);
        if (fstatus != null) w.eq(ZaloFriend::getFstatus, fstatus);
        if (deptId != null) w.eq(ZaloFriend::getDeptId, deptId);
        w.orderByDesc(ZaloFriend::getLatestMsgTime);
        IPage<ZaloFriend> p = friendMapper.selectPage(Page.of(page, size), w);
        return R.ok(p);
    }

    // ---------- 消息 ----------

    @Operation(summary = "分页查询聊天记录 (可按账号+好友过滤)")
    @GetMapping("/messages")
    public R<IPage<ZaloMessage>> listMessages(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "归属账号 (owner_zalo_id)") @RequestParam(required = false) Long ownerZaloId,
            @Parameter(description = "对端 userId (fid)") @RequestParam(required = false) Long peerUserId,
            @Parameter(description = "群 ID, 0 表示一对一") @RequestParam(required = false) Long groupId,
            @Parameter(description = "消息类型: 1=文本 2=图片 3=语音 4=视频 5=系统 7=贴图 8=卡片") @RequestParam(required = false) Integer msgType,
            @Parameter(description = "方向: 0=收 其他=发") @RequestParam(required = false) Integer direction,
            @Parameter(description = "内容包含") @RequestParam(required = false) String contentLike,
            @Parameter(description = "起始时间 yyyy-MM-dd HH:mm:ss")
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间 yyyy-MM-dd HH:mm:ss")
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime
    ) {
        LambdaQueryWrapper<ZaloMessage> w = new LambdaQueryWrapper<>();
        if (ownerZaloId != null) w.eq(ZaloMessage::getOwnerZaloId, ownerZaloId);
        if (peerUserId != null) w.eq(ZaloMessage::getPeerUserId, peerUserId);
        if (groupId != null) w.eq(ZaloMessage::getGroupId, groupId);
        if (msgType != null) w.eq(ZaloMessage::getMsgType, msgType);
        if (direction != null) w.eq(ZaloMessage::getDirection, direction);
        if (contentLike != null && !contentLike.isEmpty()) w.like(ZaloMessage::getContent, contentLike);
        if (startTime != null) w.ge(ZaloMessage::getGmtCreate, startTime);
        if (endTime != null) w.le(ZaloMessage::getGmtCreate, endTime);
        w.orderByDesc(ZaloMessage::getGmtCreate);
        IPage<ZaloMessage> p = messageMapper.selectPage(Page.of(page, size), w);
        return R.ok(p);
    }
}
