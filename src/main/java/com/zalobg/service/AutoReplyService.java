package com.zalobg.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zalobg.config.AppProps;
import com.zalobg.entity.ZaloFriend;
import com.zalobg.entity.ZaloMessage;
import com.zalobg.mapper.ZaloFriendMapper;
import com.zalobg.mapper.ZaloMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在采集聊天记录 (/api/collect/messages) 的同步流程里, 根据最新一条消息判断是否需要
 * AI 自动回复. 命中条件时调用独立的 auto-reply 微服务 ({@link AutoReplyClient}) 生成
 * 回复内容, 返回给客户端由客户端完成 sendTextMsg. 历史逻辑里客户端调 /api/ai/reply 的
 * 做法以及 zalo-bg 内置 AiReplyService 直接调 OpenAI 的做法都已废弃.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoReplyService {

    /** ged 兜底值 — 当 zalo_friend 没记录或 gender 字段为空时使用 (业务约定). */
    private static final int DEFAULT_GED = 2;

    /** msgId 级别的去重 TTL. 30 分钟内同一条消息只回复一次 (对抗 15s 轮询重复触发). */
    private static final long DEDUPE_TTL_MS = 30L * 60L * 1000L;

    private final AutoReplyClient autoReplyClient;
    private final ZaloMessageMapper messageMapper;
    private final ZaloFriendMapper friendMapper;
    private final AppProps props;

    /** msgId -> 回复时间 (epoch ms). 每次调用时顺手清理过期条目, 不依赖定时任务. */
    private final ConcurrentHashMap<Long, Long> repliedMsgIds = new ConcurrentHashMap<>();

    /**
     * 从 /api/collect/messages 的 payload 里取 "最新一条" (list[0]), 判断是否需要 AI 回复.
     * 约定 list[0] 为最新一条 — 与客户端 onChatHistory 使用的顺序一致.
     *
     * @return 若生成了 reply, 返回包含 {reply, accountid, accountNickname, fid, msgid, nickname, ged, historyCount} 的 Map;
     *         否则 Optional.empty().
     */
    public Optional<Map<String, Object>> tryAutoReply(JsonNode payload, String accountNickname) {
        if (payload == null) return Optional.empty();

        JsonNode latest = extractLatestMessage(payload);
        if (latest == null) {
            log.debug("[自动回复] 跳过: payload 里没有消息 list");
            return Optional.empty();
        }

        Integer type    = intVal(latest, "type");
        Integer issend  = intVal(latest, "issend");
        String  content = strVal(latest, "content");
        Long    accountId = longVal(latest, "accountid");
        Long    fid       = longVal(latest, "fid");
        Long    msgId     = longVal(latest, "msgid");
        String  nickname  = strVal(latest, "nickname");

        if (type == null || type != 1) {
            log.debug("[自动回复] 跳过: 非文本消息 type={}", type);
            return Optional.empty();
        }
        if (issend == null || issend != 0) {
            log.debug("[自动回复] 跳过: 非好友发来 issend={}", issend);
            return Optional.empty();
        }
        if (content == null || content.isBlank()) {
            log.debug("[自动回复] 跳过: 内容为空");
            return Optional.empty();
        }
        if (accountId == null || fid == null || msgId == null) {
            log.debug("[自动回复] 跳过: 缺少 accountid/fid/msgid (accountId={}, fid={}, msgId={})",
                    accountId, fid, msgId);
            return Optional.empty();
        }

        cleanupExpired();
        if (repliedMsgIds.putIfAbsent(msgId, System.currentTimeMillis()) != null) {
            log.info("[自动回复] 跳过: msgId={} 已在 30 分钟内被回复过 (dedupe)", msgId);
            return Optional.empty();
        }

        // 从进 dedupe 那一刻起, 任意下游失败 (DB 查询 / AI 调用) 都要回滚 dedupe 条目,
        // 否则这条 msgId 会被"假占位"锁住 30 分钟, 实际却没回复成功. 同时把异常吞掉不外抛 —
        // 本方法由 CollectController 在 collectMessages 入库成功后再调用, 绝不能让这里的失败
        // 把已经落库的采集结果变成 500.
        Integer ged;
        List<AutoReplyClient.HistoryMessage> history;
        String reply;
        try {
            ged = lookupFriendGed(accountId, fid);
            log.info("[自动回复] 触发: accountId={}, accountNickname={}, fid={}, nickname={}, ged={}, msgId={}, content={}",
                    accountId, accountNickname, fid, nickname, ged, msgId, content);

            history = loadHistory(accountId, fid, msgId);
            log.info("[自动回复] 上下文: 取到 {} 条历史消息", history.size());

            reply = autoReplyClient.generateReply(content, history);
        } catch (Exception e) {
            repliedMsgIds.remove(msgId);
            log.warn("[自动回复] 生成 reply 失败 (dedupe 已撤回, 不返回 autoReply): {}",
                    e.getMessage(), e);
            return Optional.empty();
        }
        log.info("[自动回复] AI 回复生成: {}", reply);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("reply", reply);
        ret.put("accountid", accountId);
        if (accountNickname != null) ret.put("accountNickname", accountNickname);
        ret.put("fid", fid);
        ret.put("msgid", msgId);
        if (nickname != null) ret.put("nickname", nickname);
        ret.put("ged", ged);
        ret.put("historyCount", history.size());
        return Optional.of(ret);
    }

    /** list[0] = 最新一条 (onChatHistory 约定, 和原 chunk-f6588e50 保持一致). */
    private JsonNode extractLatestMessage(JsonNode payload) {
        JsonNode list = null;
        if (payload.isArray()) {
            list = payload;
        } else if (payload.has("data")) {
            JsonNode d = payload.get("data");
            if (d.isArray()) {
                list = d;
            } else if (d.isObject() && d.has("list") && d.get("list").isArray()) {
                list = d.get("list");
            }
        }
        if (list == null && payload.has("list") && payload.get("list").isArray()) {
            list = payload.get("list");
        }
        if (list == null || list.size() == 0) return null;
        return list.get(0);
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        repliedMsgIds.entrySet().removeIf(e -> now - e.getValue() > DEDUPE_TTL_MS);
    }

    /** 拉历史对话作为上下文, 按 owner/peer 维度, 最近 N 条, 老→新. */
    private List<AutoReplyClient.HistoryMessage> loadHistory(Long ownerId, Long peerId, Long currentMsgId) {
        int limit = Math.max(1, props.getAutoReply().getHistoryLimit());
        QueryWrapper<ZaloMessage> qw = new QueryWrapper<>();
        qw.eq("owner_zalo_id", ownerId)
          .eq("peer_user_id",  peerId)
          .eq("msg_type",      1)
          .ne("deleted",       1);
        if (currentMsgId != null) {
            qw.ne("msg_id", currentMsgId);
        }
        qw.orderByDesc("gmt_create").last("limit " + limit);

        List<ZaloMessage> rows = messageMapper.selectList(qw);
        Collections.reverse(rows);

        List<AutoReplyClient.HistoryMessage> out = new ArrayList<>(rows.size());
        for (ZaloMessage m : rows) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            String role = (m.getIsSend() != null && m.getIsSend() == 1) ? "assistant" : "user";
            out.add(new AutoReplyClient.HistoryMessage(role, m.getContent()));
        }
        return out;
    }

    /** 查 zalo_friend.gender 作为 ged 标识, 查不到或为空返回兜底 2. */
    private Integer lookupFriendGed(Long ownerId, Long friendUserId) {
        QueryWrapper<ZaloFriend> qw = new QueryWrapper<>();
        qw.eq("owner_zalo_id",  ownerId)
          .eq("friend_user_id", friendUserId)
          .ne("deleted",        1)
          .last("limit 1");
        ZaloFriend f = friendMapper.selectOne(qw);
        if (f == null || f.getGender() == null) return DEFAULT_GED;
        return f.getGender();
    }

    private static Long longVal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asLong();
        if (v.isTextual()) {
            try { return Long.parseLong(v.asText()); } catch (Exception ignore) { return null; }
        }
        return null;
    }

    private static Integer intVal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asInt();
        if (v.isTextual()) {
            try { return Integer.parseInt(v.asText()); } catch (Exception ignore) { return null; }
        }
        return null;
    }

    private static String strVal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }
}
