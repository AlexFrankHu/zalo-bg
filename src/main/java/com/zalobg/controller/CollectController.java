package com.zalobg.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zalobg.common.R;
import com.zalobg.service.AutoReplyService;
import com.zalobg.service.CollectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Tag(name = "采集接口 (Collect)", description = "客户端推送 Zalo WebSocket 原始数据入库; 需带 Header X-Collect-Token")
@RestController
@RequestMapping("/api/collect")
@RequiredArgsConstructor
public class CollectController {

    private final CollectService collectService;
    private final AutoReplyService autoReplyService;

    @Operation(summary = "采集账号列表 (WS code=1)",
            description = "入参可直接是 WS 返回体 {code:1,data:[...]} 或者只有 data 数组")
    @PostMapping("/accounts")
    public R<Map<String, Object>> accounts(@RequestBody JsonNode payload) {
        int n = collectService.collectAccounts(payload);
        return R.ok(Map.of("upserted", n));
    }

    @Operation(summary = "采集账号的好友列表 (WS code=7)",
            description = "入参同上, 每条自带 accountId 说明归属哪个账号")
    @PostMapping("/friends")
    public R<Map<String, Object>> friends(@RequestBody JsonNode payload) {
        int n = collectService.collectFriends(payload);
        return R.ok(Map.of("upserted", n));
    }

    @Operation(summary = "采集聊天记录 (WS code=17), 可选触发 AI 自动回复",
            description = "入参可是 {code:17,data:{list:[...]}} 或 {list:[...]} 或直接 [...] 数组. " +
                          "若 body 顶层带 `autoReply:true`, 服务端会在入库后分析 list[0] 是否是好友发来的文本, " +
                          "命中条件时调用 AI 生成回复并以 `data.autoReply = {reply,accountid,fid,msgid,...}` 返回, " +
                          "客户端收到后自行 sendTextMsg. 顶层可传 `accountNickname` 作为当前账号昵称日志/AI 上下文.")
    @PostMapping("/messages")
    public R<Map<String, Object>> messages(@RequestBody JsonNode payload) {
        int n = collectService.collectMessages(payload);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("upserted", n);

        if (payload != null && payload.path("autoReply").asBoolean(false)) {
            String accountNickname = extractAccountNickname(payload);
            // tryAutoReply 内部已经把所有异常吞成 Optional.empty, 这里再加一层兜底保护:
            // 任何意外抛出都不应影响 collectMessages 已经完成的入库结果, 客户端仍然能拿到
            // {upserted: N}, 只是 autoReply 字段不出现, 下一轮 15s 轮询会重试.
            try {
                autoReplyService.tryAutoReply(payload, accountNickname)
                        .ifPresent(ar -> ret.put("autoReply", ar));
            } catch (Exception e) {
                log.warn("[自动回复] tryAutoReply 意外异常, 仅返回 upserted: {}", e.getMessage(), e);
            }
        }
        return R.ok(ret);
    }

    private String extractAccountNickname(JsonNode payload) {
        JsonNode v = payload.hasNonNull("accountNickname") ? payload.get("accountNickname") : null;
        if (v == null) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }
}
