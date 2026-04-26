package com.zalobg.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zalobg.entity.ZaloAccount;
import com.zalobg.entity.ZaloFriend;
import com.zalobg.entity.ZaloMessage;
import com.zalobg.mapper.ZaloAccountMapper;
import com.zalobg.mapper.ZaloFriendMapper;
import com.zalobg.mapper.ZaloMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectService {

    private final ZaloAccountMapper accountMapper;
    private final ZaloFriendMapper friendMapper;
    private final ZaloMessageMapper messageMapper;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * 采集 WS code=1 的 data 数组 (账号列表)
     * 入参结构:
     *   {"code":1,"data":[{ID,account,nickName,...}, ...]}  -- 直接把整个响应传进来
     * 或者:
     *   [{ID,account,...}, ...]  -- 只传 data 数组
     */
    @Transactional
    public int collectAccounts(JsonNode payload) {
        JsonNode list = extractDataList(payload);
        if (list == null || !list.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode n : list) {
            ZaloAccount a = mapAccount(n);
            if (a == null || a.getZaloId() == null) continue;
            ZaloAccount exist = accountMapper.selectOne(
                    new LambdaQueryWrapper<ZaloAccount>()
                            .eq(ZaloAccount::getZaloId, a.getZaloId())
                            .last("LIMIT 1"));
            if (exist == null) {
                a.setCollectedAt(LocalDateTime.now());
                accountMapper.insert(a);
            } else {
                a.setId(exist.getId());
                a.setCollectedAt(LocalDateTime.now());
                accountMapper.updateById(a);
            }
            count++;
        }
        return count;
    }

    /**
     * 采集 WS code=7 的 data 数组 (好友列表)
     * 每条里自带 accountId 说明归属哪个账号
     */
    @Transactional
    public int collectFriends(JsonNode payload) {
        JsonNode list = extractDataList(payload);
        if (list == null || !list.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode n : list) {
            ZaloFriend f = mapFriend(n);
            if (f == null || f.getOwnerZaloId() == null || f.getFriendUserId() == null) continue;
            ZaloFriend exist = friendMapper.selectOne(
                    new LambdaQueryWrapper<ZaloFriend>()
                            .eq(ZaloFriend::getOwnerZaloId, f.getOwnerZaloId())
                            .eq(ZaloFriend::getFriendUserId, f.getFriendUserId())
                            .last("LIMIT 1"));
            if (exist == null) {
                f.setCollectedAt(LocalDateTime.now());
                friendMapper.insert(f);
            } else {
                f.setId(exist.getId());
                f.setCollectedAt(LocalDateTime.now());
                friendMapper.updateById(f);
            }
            count++;
        }
        return count;
    }

    /**
     * 采集 WS code=17 的 data.list 数组 (聊天记录分页列表)
     * 入参结构:
     *   {"code":17,"data":{"list":[...], "total":..., "page":..., "pageSize":...}}
     * 或者:
     *   {"list":[...]}
     * 或者直接:
     *   [{msgid,content,...}, ...]
     */
    @Transactional
    public int collectMessages(JsonNode payload) {
        JsonNode list = extractMessageList(payload);
        if (list == null || !list.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode n : list) {
            ZaloMessage m = mapMessage(n);
            if (m == null || m.getOwnerZaloId() == null || m.getPeerUserId() == null || m.getMsgId() == null) continue;
            ZaloMessage exist = messageMapper.selectOne(
                    new LambdaQueryWrapper<ZaloMessage>()
                            .eq(ZaloMessage::getOwnerZaloId, m.getOwnerZaloId())
                            .eq(ZaloMessage::getPeerUserId, m.getPeerUserId())
                            .eq(ZaloMessage::getMsgId, m.getMsgId())
                            .last("LIMIT 1"));
            if (exist == null) {
                m.setCollectedAt(LocalDateTime.now());
                messageMapper.insert(m);
                count++;
            } else {
                // 已存在则更新关键字段 (seen/isread/issend 等状态可能变)
                m.setId(exist.getId());
                m.setCollectedAt(LocalDateTime.now());
                messageMapper.updateById(m);
            }
        }
        return count;
    }

    private JsonNode extractDataList(JsonNode payload) {
        if (payload == null) return null;
        if (payload.isArray()) return payload;
        if (payload.has("data") && payload.get("data").isArray()) return payload.get("data");
        return null;
    }

    private JsonNode extractMessageList(JsonNode payload) {
        if (payload == null) return null;
        if (payload.isArray()) return payload;
        if (payload.has("data")) {
            JsonNode data = payload.get("data");
            if (data.isArray()) return data;
            if (data.isObject() && data.has("list") && data.get("list").isArray()) return data.get("list");
        }
        if (payload.has("list") && payload.get("list").isArray()) return payload.get("list");
        return null;
    }

    private ZaloAccount mapAccount(JsonNode n) {
        try {
            ZaloAccount a = new ZaloAccount();
            a.setZaloId(longVal(n, "ID"));
            a.setAccount(strVal(n, "account"));
            a.setNickName(strVal(n, "nickName"));
            a.setRegion(strVal(n, "region"));
            a.setPassword(strVal(n, "password"));
            a.setSex(intVal(n, "sex"));
            a.setHeadimg(strVal(n, "headimg"));
            a.setOnline(intVal(n, "online"));
            a.setAccountStatus(intVal(n, "accountStatus"));
            a.setRemark(strVal(n, "remark"));
            a.setProxyIp(strVal(n, "proxyip"));
            a.setProxyId(longVal(n, "proxyId"));
            a.setFriendsSum(intVal(n, "friendsum"));
            a.setGroupSum(intVal(n, "groupsum"));
            a.setMsgSum(intVal(n, "msgSum"));
            a.setGroupId(longVal(n, "groupId"));
            JsonNode group = n.get("group");
            if (group != null && group.isObject()) {
                a.setGroupName(strVal(group, "groupName"));
            }
            a.setDeptId(longVal(n, "deptId"));
            a.setBindId(longVal(n, "bindId"));
            a.setSdbo(strVal(n, "sdbo"));
            a.setWxid(strVal(n, "wxid"));
            a.setIsShop(intVal(n, "isshop"));
            a.setRealName(intVal(n, "realName"));
            a.setCreateBy(strVal(n, "createBy"));
            a.setGmtCreate(dateTimeVal(n, "CreatedAt"));
            a.setGmtUpdate(dateTimeVal(n, "UpdatedAt"));
            a.setRawJson(n.toString());
            return a;
        } catch (Exception e) {
            log.warn("map account fail: {}", n, e);
            return null;
        }
    }

    private ZaloFriend mapFriend(JsonNode n) {
        try {
            ZaloFriend f = new ZaloFriend();
            f.setMongoId(strVal(n, "_id"));
            f.setOwnerZaloId(longVal(n, "accountId"));
            f.setOwnerAccount(strVal(n, "account"));
            f.setFriendUserId(longVal(n, "userId"));
            f.setFstatus(intVal(n, "fstatus"));
            f.setRemark(strVal(n, "remark"));
            f.setDeptId(longVal(n, "deptId"));
            f.setMsgSum(intVal(n, "msgSum"));
            f.setLatestMsgTime(dateTimeVal(n, "latestMsgTime"));
            f.setGmtCreate(dateTimeVal(n, "CreatedAt"));
            JsonNode info = n.get("info");
            if (info != null && info.isObject()) {
                f.setFriendUuid(longVal(info, "uuid"));
                f.setDisplayName(strVal(info, "dpn"));
                f.setUserName(strVal(info, "usr"));
                f.setAvatar(strVal(info, "avt"));
                f.setCover(strVal(info, "cover"));
                f.setGender(intVal(info, "ged"));
                f.setPhone(strVal(info, "phone"));
                f.setStatusText(strVal(info, "stt"));
                f.setActionCount(intVal(info, "ac"));
            }
            f.setRawJson(n.toString());
            return f;
        } catch (Exception e) {
            log.warn("map friend fail: {}", n, e);
            return null;
        }
    }

    private ZaloMessage mapMessage(JsonNode n) {
        try {
            ZaloMessage m = new ZaloMessage();
            m.setMongoId(strVal(n, "_id"));
            m.setMsgId(longVal(n, "msgid"));
            m.setOwnerZaloId(longVal(n, "accountid"));
            m.setOwnerAccount(strVal(n, "account"));
            m.setPeerUserId(longVal(n, "fid"));
            m.setGroupId(longVal(n, "groupId"));
            m.setContent(strVal(n, "content"));
            m.setMsgType(intVal(n, "type"));
            m.setDirection(intVal(n, "from"));
            m.setIsSend(intVal(n, "issend"));
            m.setIsRead(intVal(n, "isread"));
            m.setSeen(intVal(n, "seen"));
            m.setNickname(strVal(n, "nickname"));
            m.setDst(strVal(n, "dst"));
            m.setDescribe(strVal(n, "describe"));
            m.setBindId(longVal(n, "bind_id"));
            m.setDeptId(longVal(n, "deptId"));
            m.setGmtCreate(dateTimeVal(n, "CreatedAt"));
            m.setRawJson(n.toString());
            return m;
        } catch (Exception e) {
            log.warn("map message fail: {}", n, e);
            return null;
        }
    }

    // ---------- helpers ----------

    private static String strVal(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Integer intVal(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static Long longVal(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asLong();
        try {
            return Long.parseLong(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime dateTimeVal(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        if (s == null || s.isEmpty()) return null;
        try {
            // "2026-04-23T16:10:47+08:00" / "2026-04-24T07:09:46.614Z"
            return Instant.parse(s.length() > 10 && !s.endsWith("Z") && !s.contains("+") && !s.contains("-") ? s + "Z" : normalize(s))
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s.replace(" ", "T"));
            } catch (Exception ex) {
                log.debug("parse datetime fail: {} -> {}", key, s);
                return null;
            }
        }
    }

    private static String normalize(String s) {
        // Instant.parse 需要 ISO-8601 带时区/Z
        if (s.endsWith("Z")) return s;
        if (s.matches(".*[+-]\\d{2}:?\\d{2}$")) return s;
        if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")) return s + "Z";
        return s;
    }
}
