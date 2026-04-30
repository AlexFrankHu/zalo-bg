package com.zalobg.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zalobg.common.ApiException;
import com.zalobg.common.R;
import com.zalobg.config.AppProps;
import com.zalobg.entity.ZaloFriend;
import com.zalobg.entity.ZaloMessage;
import com.zalobg.mapper.ZaloFriendMapper;
import com.zalobg.mapper.ZaloMessageMapper;
import com.zalobg.service.AiReplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 自动回复接口. 前端把 WS code=3 收到的新消息整体转发过来,
 * 后端调 OpenAI 兼容接口 (默认 gpt-4o-mini) 生成围绕游戏主题的回复后返回.
 *
 * 鉴权方式同 /api/collect/* — 需带 Header X-Collect-Token
 * (路径前缀 /api/ai/ 已在 SecurityConfig + JwtAuthFilter 中走相同的 token 校验).
 */
@Slf4j
@Tag(name = "AI 接口", description = "调 AI 生成自动回复内容 (默认 OpenAI gpt-4o-mini); 需带 Header X-Collect-Token")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiReplyService aiReplyService;
    private final ZaloMessageMapper messageMapper;
    private final ZaloFriendMapper friendMapper;
    private final AppProps props;

    /** ged 兜底值 — 当 zalo_friend 没记录或 gender 字段为空时使用 (业务约定). */
    private static final int DEFAULT_GED = 2;

    @Operation(summary = "根据收到的消息生成 AI 回复 (游戏主题, 自动结合历史聊天记录)",
            description = "入参可以是 WS code=3 完整体 {code:3,data:{accountid,fid,content,...}} 或者 {message:\"hello\"}. " +
                          "若入参里带 accountid + fid, 后端会自动从 zalo_message 表按 owner/peer 取最近 N 条作为上下文.")
    @PostMapping("/reply")
    public R<Map<String, Object>> reply(@RequestBody JsonNode payload) {
        String userMessage = extractMessage(payload);
        Long accountId = extractLong(payload, "accountid");
        Long fid       = extractLong(payload, "fid");
        Long msgId     = extractLong(payload, "msgid");
        String accountNickname = extractAccountNickname(payload);
        Integer ged = lookupFriendGed(accountId, fid);

        log.info("AI reply 请求, accountId={}, accountNickname={}, fid={}, ged={}, userMessage={}",
                accountId, accountNickname, fid, ged, userMessage);

        // 拉历史对话作为上下文 (按 owner/peer 维度, 最近 N 条, 老到新)
        List<AiReplyService.HistoryMessage> history = loadHistory(accountId, fid, msgId);
        log.info("AI reply 上下文: 取到 {} 条历史消息", history.size());

        String reply = aiReplyService.generateReply(userMessage, history);
        log.info("AI reply 完成, reply={}", reply);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("input",        userMessage);
        ret.put("reply",        reply);
        ret.put("historyCount", history.size());
        if (accountId != null)        ret.put("accountid",       accountId);
        if (accountNickname != null)  ret.put("accountNickname", accountNickname);
        if (fid != null)              ret.put("fid",             fid);
        if (msgId != null)            ret.put("msgid",           msgId);
        ret.put("ged", ged);
        JsonNode data = payload == null ? null : payload.get("data");
        if (data != null && data.isObject() && data.hasNonNull("nickname")) {
            ret.put("nickname", data.get("nickname").asText());
        }
        return R.ok(ret);
    }

    private List<AiReplyService.HistoryMessage> loadHistory(Long ownerId, Long peerId, Long currentMsgId) {
        if (ownerId == null || peerId == null) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, props.getAi().getHistoryLimit());
        QueryWrapper<ZaloMessage> qw = new QueryWrapper<>();
        qw.eq("owner_zalo_id", ownerId)
          .eq("peer_user_id",  peerId)
          .eq("msg_type",      1)               // 只取文本消息
          .ne("deleted",       1);
        if (currentMsgId != null) {
            // 排除本轮刚收到的消息(可能已经入库)
            qw.ne("msg_id", currentMsgId);
        }
        qw.orderByDesc("gmt_create").last("limit " + limit);

        List<ZaloMessage> rows = messageMapper.selectList(qw);
        // 倒序拿到最新 N 条, 翻成 老→新
        Collections.reverse(rows);

        List<AiReplyService.HistoryMessage> out = new ArrayList<>(rows.size());
        for (ZaloMessage m : rows) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            // isSend == 1 → 我方发出 (assistant); 0 → 对方发来 (user)
            String role = (m.getIsSend() != null && m.getIsSend() == 1) ? "assistant" : "user";
            out.add(new AiReplyService.HistoryMessage(role, m.getContent()));
        }
        return out;
    }

    private String extractMessage(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            throw new ApiException(400, "请求体为空");
        }
        JsonNode data = payload.get("data");
        if (data != null && data.isObject() && data.hasNonNull("content")) {
            String s = data.get("content").asText();
            if (!s.isBlank()) return s;
        }
        if (payload.hasNonNull("message")) {
            String s = payload.get("message").asText();
            if (!s.isBlank()) return s;
        }
        if (payload.hasNonNull("content")) {
            String s = payload.get("content").asText();
            if (!s.isBlank()) return s;
        }
        throw new ApiException(400, "请求体里没有 data.content / message / content 字段");
    }

    private Long extractLong(JsonNode payload, String field) {
        if (payload == null) return null;
        // 优先从 data.<field> 取, 其次顶层
        JsonNode data = payload.get("data");
        JsonNode v = (data != null && data.isObject() && data.hasNonNull(field)) ? data.get(field) : payload.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asLong();
        if (v.isTextual()) {
            try { return Long.parseLong(v.asText()); } catch (Exception ignore) { return null; }
        }
        return null;
    }

    /** 从请求里抽 accountNickname (顶层优先, 兼容 data.accountNickname). */
    private String extractAccountNickname(JsonNode payload) {
        if (payload == null) return null;
        JsonNode v = payload.hasNonNull("accountNickname") ? payload.get("accountNickname") : null;
        if (v == null) {
            JsonNode data = payload.get("data");
            if (data != null && data.isObject() && data.hasNonNull("accountNickname")) {
                v = data.get("accountNickname");
            }
        }
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 查 zalo_friend.gender 作为目标好友的 ged 标识, 查不到或为空时返回兜底值 2. */
    private Integer lookupFriendGed(Long ownerId, Long friendUserId) {
        if (ownerId == null || friendUserId == null) {
            return DEFAULT_GED;
        }
        QueryWrapper<ZaloFriend> qw = new QueryWrapper<>();
        qw.eq("owner_zalo_id",  ownerId)
          .eq("friend_user_id", friendUserId)
          .ne("deleted",        1)
          .last("limit 1");
        ZaloFriend f = friendMapper.selectOne(qw);
        if (f == null || f.getGender() == null) {
            return DEFAULT_GED;
        }
        return f.getGender();
    }
}
