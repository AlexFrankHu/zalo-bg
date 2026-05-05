package com.zalobg.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zalobg.config.AppProps;
import com.zalobg.entity.ZaloAccount;
import com.zalobg.entity.ZaloFriend;
import com.zalobg.entity.ZaloMessage;
import com.zalobg.mapper.ZaloAccountMapper;
import com.zalobg.mapper.ZaloFriendMapper;
import com.zalobg.mapper.ZaloMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在采集聊天记录 (/api/collect/messages) 的同步流程里, 根据最新一条消息 + 会话历史
 * 判断是否需要 AI 自动回复. 命中条件时调用独立的 auto-reply 微服务
 * ({@link AutoReplyClient}) 生成回复内容, 返回给客户端由客户端完成 sendTextMsg.
 *
 * 触发策略 (按 state 划分, 每次 collect 都重判):
 *   state=0: 最新一条 = 好友主动发的非系统文本; 走原 msgId 级别去重 (30 分钟).
 *
 *   分支 A (双方都没发过 msg_type=1 的文本):
 *     如果存在 msg_type=5 (添加好友系统消息):
 *       距系统消息 >15 分钟 → state=1; 否则不发
 *     如果不存在 msg_type=5: → state=1 (无时间限制)
 *
 *   分支 B (好友从未回过, 只有自己发过):
 *     state=2: 我发 1 条, 距末次我发 ≥1h
 *     state=3: 我发 2 条, 距末次我发 ≥3h
 *     state=4: 我发 3 条, 距末次我发 ≥24h
 *     (我发 ≥4 条不再有 state, 不再骚扰)
 *
 *   分支 C (好友回过, 末位是我):
 *     state=5: 末尾连续 1 条我发, 距末次我发 ∈ [3h, 12h]
 *     state=6: 末尾连续 2 条我发, 距末次我发 ∈ [12h, 24h]
 *     state=7: 末尾连续 3 条我发, 距末次我发 ∈ [24h, 48h]
 *     state=8: 末尾连续 ≥4 条我发, 距末次我发 ∈ [48h, 72h]
 *     (其它区间一律不触发)
 *
 * state 1-8 用 (accountId, fid, state) 复合键 + 1h TTL 做去重, 防止 15s 轮询狂触发.
 *
 * 历史逻辑里客户端调 /api/ai/reply 的做法以及 zalo-bg 内置 AiReplyService 直接调
 * OpenAI 的做法都已废弃.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoReplyService {

    /** ged 兜底值 — 当 zalo_friend 没记录或 gender 字段为空时使用 (业务约定). */
    private static final int DEFAULT_GED = 2;

    /** state=0 表示 "被动回复好友消息" — 最新一条 = 好友主动发的非系统文本. */
    private static final int STATE_PASSIVE_REPLY = 0;

    /** msgId 级别的去重 TTL (state=0). 30 分钟内同一条消息只回复一次. */
    private static final long PASSIVE_DEDUPE_TTL_MS = 30L * 60L * 1000L;

    /** (accountId, fid, state) 级别的去重 TTL (state=1-8). 1 小时内同 state 不再重发. */
    private static final long PROACTIVE_DEDUPE_TTL_MS = 60L * 60L * 1000L;

    private static final long MIN_MS  = 60L * 1000L;
    private static final long HOUR_MS = 60L * MIN_MS;

    private final AutoReplyClient autoReplyClient;
    private final ZaloMessageMapper messageMapper;
    private final ZaloFriendMapper friendMapper;
    private final ZaloAccountMapper accountMapper;
    private final AppProps props;

    /** msgId -> 回复时间 (epoch ms). 每次调用时顺手清理过期条目. */
    private final ConcurrentHashMap<Long, Long> repliedMsgIds = new ConcurrentHashMap<>();

    /** "accountId:fid:s{state}" -> 触发时间 (epoch ms). state 1-8 各自独立. */
    private final ConcurrentHashMap<String, Long> proactiveTriggered = new ConcurrentHashMap<>();

    /**
     * 从 /api/collect/messages 的 payload 里取 "最新一条" (list[0]),
     * 优先按 state=0 判断 (好友主动发的非系统文本); 不命中再按 state 1-8 评估.
     *
     * @return 若生成了 reply, 返回包含 {reply, accountid, accountNickname, fid, msgid?, nickname, ged, state, historyCount}
     *         的 Map; 否则 Optional.empty().
     */
    public Optional<Map<String, Object>> tryAutoReply(JsonNode payload, String accountNickname) {
        if (payload == null) return Optional.empty();

        JsonNode latest = extractLatestMessage(payload);
        if (latest == null) {
            log.debug("[自动回复] 跳过: payload 里没有消息 list");
            return Optional.empty();
        }

        Long accountId = longVal(latest, "accountid");
        Long fid       = longVal(latest, "fid");
        if (accountId == null || fid == null) {
            log.debug("[自动回复] 跳过: 缺少 accountid/fid (accountId={}, fid={})", accountId, fid);
            return Optional.empty();
        }

        Integer type    = intVal(latest, "type");
        Integer issend  = intVal(latest, "issend");
        String  content = strVal(latest, "content");
        Long    msgId   = longVal(latest, "msgid");
        String  nickname = strVal(latest, "nickname");

        boolean isFriendText = type != null && type == 1
                && issend != null && issend == 0
                && content != null && !content.isBlank()
                && msgId != null;

        if (isFriendText) {
            return tryPassiveReply(accountId, fid, msgId, content, nickname, accountNickname);
        }
        log.debug("[自动回复] state=0 不匹配 (type={}, issend={}, contentBlank={}, msgIdNull={}), 进入主动评估",
                type, issend, content == null || content.isBlank(), msgId == null);
        return tryProactiveReply(accountId, fid, nickname, accountNickname);
    }

    // ---------------------------------------------------------------------
    // state=0: 被动回复
    // ---------------------------------------------------------------------

    private Optional<Map<String, Object>> tryPassiveReply(Long accountId, Long fid, Long msgId,
                                                          String content, String nickname,
                                                          String accountNickname) {
        cleanupPassiveDedupe();
        if (repliedMsgIds.putIfAbsent(msgId, System.currentTimeMillis()) != null) {
            log.info("[自动回复] 跳过: msgId={} 已在 30 分钟内被回复过 (dedupe)", msgId);
            return Optional.empty();
        }

        // 从进 dedupe 那一刻起, 任意下游失败 (DB / AI) 都要回滚 dedupe 条目, 否则这条
        // msgId 会被"假占位"锁住 30 分钟. 同时把异常吞掉不外抛 — 本方法由 CollectController
        // 在 collectMessages 入库成功后再调用, 绝不能让这里的失败把已落库的采集结果变成 500.
        Integer ged;
        Integer myGed;
        List<AutoReplyClient.HistoryMessage> history;
        String reply;
        int state = STATE_PASSIVE_REPLY;
        try {
            ged   = lookupFriendGed(accountId, fid);
            myGed = lookupAccountSex(accountId);
            log.info("[自动回复] 触发: accountId={}, accountNickname={}, myGed={}, fid={}, nickname={}, ged={}, msgId={}, state={}, content={}",
                    accountId, accountNickname, myGed, fid, nickname, ged, msgId, state, content);

            history = loadHistory(accountId, fid, msgId);
            log.info("[自动回复] 上下文: 取到 {} 条历史消息", history.size());

            reply = autoReplyClient.generateReply(content, history,
                    nickname, ged, accountNickname, myGed, state);
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
        ret.put("state", state);
        ret.put("historyCount", history.size());
        return Optional.of(ret);
    }

    // ---------------------------------------------------------------------
    // state 1-8: 主动跟进
    // ---------------------------------------------------------------------

    private Optional<Map<String, Object>> tryProactiveReply(Long accountId, Long fid,
                                                            String nicknameMaybe,
                                                            String accountNickname) {
        Optional<Integer> stateOpt = evaluateProactiveState(accountId, fid);
        if (stateOpt.isEmpty()) {
            log.debug("[自动回复-主动] 未命中 state 1-8: accountId={}, fid={}", accountId, fid);
            return Optional.empty();
        }
        int state = stateOpt.get();

        cleanupProactiveDedupe();
        String dedupeKey = accountId + ":" + fid + ":s" + state;
        long now = System.currentTimeMillis();
        if (proactiveTriggered.putIfAbsent(dedupeKey, now) != null) {
            log.info("[自动回复-主动] 跳过: state={} 1 小时内已触发 (key={})", state, dedupeKey);
            return Optional.empty();
        }

        Integer ged;
        Integer myGed;
        String nickname = nicknameMaybe;
        List<AutoReplyClient.HistoryMessage> history;
        String reply;
        try {
            ged   = lookupFriendGed(accountId, fid);
            myGed = lookupAccountSex(accountId);
            if (nickname == null || nickname.isBlank()) {
                nickname = lookupFriendNickname(accountId, fid);
            }
            log.info("[自动回复-主动] 触发: accountId={}, accountNickname={}, myGed={}, fid={}, nickname={}, ged={}, state={}",
                    accountId, accountNickname, myGed, fid, nickname, ged, state);
            history = loadHistory(accountId, fid, null);
            log.info("[自动回复-主动] 上下文: 取到 {} 条历史消息", history.size());
            // userMessage 传空字符串: auto-reply 内部会跳过本轮 user 消息, 让模型纯
            // 基于 system prompt + history + state 自己造话.
            reply = autoReplyClient.generateReply("", history, nickname, ged, accountNickname, myGed, state);
        } catch (Exception e) {
            proactiveTriggered.remove(dedupeKey);
            log.warn("[自动回复-主动] 生成 reply 失败 (dedupe 已撤回, 不返回 autoReply): {}",
                    e.getMessage(), e);
            return Optional.empty();
        }
        log.info("[自动回复-主动] AI 回复生成: state={}, reply={}", state, reply);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("reply", reply);
        ret.put("accountid", accountId);
        if (accountNickname != null) ret.put("accountNickname", accountNickname);
        ret.put("fid", fid);
        if (nickname != null) ret.put("nickname", nickname);
        ret.put("ged", ged);
        ret.put("state", state);
        ret.put("historyCount", history.size());
        return Optional.of(ret);
    }

    /**
     * 按业务规则评估 (accountId, fid) 当前是否落在 state 1-8 中的某一档.
     * 不命中返回 Optional.empty(). 详细规则见类上 javadoc.
     */
    private Optional<Integer> evaluateProactiveState(Long ownerId, Long peerId) {
        // 拉所有 msg_type=1 (文本) 消息, 老→新.
        QueryWrapper<ZaloMessage> qw = new QueryWrapper<>();
        qw.eq("owner_zalo_id", ownerId)
          .eq("peer_user_id",  peerId)
          .eq("msg_type",      1)
          .ne("deleted",       1)
          .orderByAsc("gmt_create");
        List<ZaloMessage> rows = messageMapper.selectList(qw);

        int friendCount = 0;
        int myCount = 0;
        Long lastMyMsgEpoch = null;
        for (ZaloMessage m : rows) {
            Integer s = m.getIsSend();
            if (s == null) continue;
            if (s == 1) {
                myCount++;
                // 跳过 gmt_create 为 null 的行 — toEpochMs(null) 返 Long.MAX_VALUE,
                // max-tracking 下它会作为"毒值"永久占位, 让任何后续合法时间戳都
                // 无法超过, 导致 elapsed=now-MAX_VALUE 变大负数, state 2-8 全部
                // 静默失效. 跳过即可 (myCount 仍然计入, 只是不参与 lastMyMsgEpoch).
                if (m.getGmtCreate() != null) {
                    long ts = toEpochMs(m.getGmtCreate());
                    if (lastMyMsgEpoch == null || ts > lastMyMsgEpoch) lastMyMsgEpoch = ts;
                }
            } else if (s == 0) {
                friendCount++;
            }
        }
        // trailingMyCount: 末尾连续 issend=1 的条数.
        int trailingMyCount = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            Integer s = rows.get(i).getIsSend();
            if (s != null && s == 1) trailingMyCount++;
            else break;
        }

        long now = System.currentTimeMillis();

        // 分支 A: 双方都没发过文本 → 看 msg_type=5 (添加好友系统消息).
        if (rows.isEmpty()) {
            Long sysEpoch = lookupAddFriendSystemMsgEpoch(ownerId, peerId);
            if (sysEpoch == null) {
                // 没有添加好友系统消息 → 直接 state=1 (无时间限制).
                return Optional.of(1);
            }
            // 有系统消息 → 仅当距今 >15 分钟才发, 否则等下一轮.
            if (now - sysEpoch > 15L * MIN_MS) return Optional.of(1);
            return Optional.empty();
        }

        // 分支 B: 好友从未回过文本, 只有我方发过.
        if (friendCount == 0 && lastMyMsgEpoch != null) {
            long elapsed = now - lastMyMsgEpoch;
            if (myCount == 1 && elapsed >= 1L  * HOUR_MS) return Optional.of(2);
            if (myCount == 2 && elapsed >= 3L  * HOUR_MS) return Optional.of(3);
            if (myCount == 3 && elapsed >= 24L * HOUR_MS) return Optional.of(4);
            // myCount ≥ 4: 不再发, 防止骚扰.
            return Optional.empty();
        }

        // 分支 C: 好友回过文本 (friendCount ≥ 1), 末位是我发的 (trailingMyCount ≥ 1).
        // 末位若是好友的非系统文本, 已被上层 state=0 拦走, 不会进到这里.
        if (friendCount >= 1 && trailingMyCount >= 1 && lastMyMsgEpoch != null) {
            long elapsed = now - lastMyMsgEpoch;
            if (trailingMyCount >= 4) {
                if (elapsed >= 48L * HOUR_MS && elapsed <= 72L * HOUR_MS) return Optional.of(8);
                return Optional.empty();
            }
            if (trailingMyCount == 3) {
                if (elapsed >= 24L * HOUR_MS && elapsed <= 48L * HOUR_MS) return Optional.of(7);
                return Optional.empty();
            }
            if (trailingMyCount == 2) {
                if (elapsed >= 12L * HOUR_MS && elapsed <= 24L * HOUR_MS) return Optional.of(6);
                return Optional.empty();
            }
            // trailingMyCount == 1
            if (elapsed >= 3L * HOUR_MS && elapsed <= 12L * HOUR_MS) return Optional.of(5);
            return Optional.empty();
        }

        return Optional.empty();
    }

    /**
     * 找 (owner, peer) 的最新一条 msg_type=5 (添加好友系统消息) 的 gmt_create.
     * 没有则返回 null. 用于分支 A 的 state=1 判定.
     */
    private Long lookupAddFriendSystemMsgEpoch(Long ownerId, Long peerId) {
        QueryWrapper<ZaloMessage> qw = new QueryWrapper<>();
        qw.eq("owner_zalo_id", ownerId)
          .eq("peer_user_id",  peerId)
          .eq("msg_type",      5)
          .ne("deleted",       1)
          .orderByDesc("gmt_create")
          .last("limit 1");
        ZaloMessage m = messageMapper.selectOne(qw);
        if (m == null || m.getGmtCreate() == null) return null;
        return toEpochMs(m.getGmtCreate());
    }

    // ---------------------------------------------------------------------
    // 公共辅助
    // ---------------------------------------------------------------------

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

    private void cleanupPassiveDedupe() {
        long now = System.currentTimeMillis();
        repliedMsgIds.entrySet().removeIf(e -> now - e.getValue() > PASSIVE_DEDUPE_TTL_MS);
    }

    private void cleanupProactiveDedupe() {
        long now = System.currentTimeMillis();
        proactiveTriggered.entrySet().removeIf(e -> now - e.getValue() > PROACTIVE_DEDUPE_TTL_MS);
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

    /**
     * 查 zalo_account.sex 作为我方性别 (myGed). 查不到或为空返回 null,
     * 由下游 auto-reply 自行处理 (审计日志会打 "myGed=null").
     */
    private Integer lookupAccountSex(Long accountId) {
        if (accountId == null) return null;
        QueryWrapper<ZaloAccount> qw = new QueryWrapper<>();
        qw.eq("zalo_id", accountId)
          .ne("deleted", 1)
          .last("limit 1");
        ZaloAccount acc = accountMapper.selectOne(qw);
        return (acc == null) ? null : acc.getSex();
    }

    /** 查 zalo_friend.gender 作为 ged 标识, 查不到或为空返回兜底 2. */
    private Integer lookupFriendGed(Long ownerId, Long friendUserId) {
        ZaloFriend f = lookupFriend(ownerId, friendUserId);
        if (f == null || f.getGender() == null) return DEFAULT_GED;
        return f.getGender();
    }

    /** 查 zalo_friend.display_name 作为好友昵称兜底 (state 1-8 时 payload 不一定有 nickname). */
    private String lookupFriendNickname(Long ownerId, Long friendUserId) {
        ZaloFriend f = lookupFriend(ownerId, friendUserId);
        if (f == null) return null;
        return f.getDisplayName();
    }

    private ZaloFriend lookupFriend(Long ownerId, Long friendUserId) {
        QueryWrapper<ZaloFriend> qw = new QueryWrapper<>();
        qw.eq("owner_zalo_id",  ownerId)
          .eq("friend_user_id", friendUserId)
          .ne("deleted",        1)
          .last("limit 1");
        return friendMapper.selectOne(qw);
    }

    /**
     * LocalDateTime → epoch ms. null 时返回 Long.MAX_VALUE 而不是 0L —
     * 0L (1970) 会让 (now - ts) ≈ 56 年, 过任何时间阈值, 错误触发 state 2-8;
     * MAX_VALUE 让 (now - ts) 变成大负数, 永远过不了阈值, 等于"该消息时间未知, 不触发".
     */
    private static long toEpochMs(LocalDateTime t) {
        if (t == null) return Long.MAX_VALUE;
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
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
