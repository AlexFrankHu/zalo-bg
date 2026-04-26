package com.zalobg.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zalobg.common.R;
import com.zalobg.service.CollectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "采集接口 (Collect)", description = "客户端推送 Zalo WebSocket 原始数据入库; 需带 Header X-Collect-Token")
@RestController
@RequestMapping("/api/collect")
@RequiredArgsConstructor
public class CollectController {

    private final CollectService collectService;

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

    @Operation(summary = "采集聊天记录 (WS code=17)",
            description = "入参可是 {code:17,data:{list:[...]}} 或 {list:[...]} 或直接 [...] 数组")
    @PostMapping("/messages")
    public R<Map<String, Object>> messages(@RequestBody JsonNode payload) {
        int n = collectService.collectMessages(payload);
        return R.ok(Map.of("upserted", n));
    }
}
