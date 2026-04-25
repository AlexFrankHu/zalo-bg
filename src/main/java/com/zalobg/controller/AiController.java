package com.zalobg.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zalobg.common.ApiException;
import com.zalobg.common.R;
import com.zalobg.service.AiReplyService;
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

    @Operation(summary = "根据收到的消息生成 AI 回复 (游戏主题)",
            description = "入参可以是 WS code=3 完整体 {code:3,data:{...}} 或者 {message:\"hello\"}." +
                          " 优先取 data.content, 其次 message, 最后整个 body 当文本.")
    @PostMapping("/reply")
    public R<Map<String, Object>> reply(@RequestBody JsonNode payload) {
        String userMessage = extractMessage(payload);
        log.info("AI reply 请求, userMessage={}", userMessage);

        String reply = aiReplyService.generateReply(userMessage);
        log.info("AI reply 完成, reply={}", reply);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("input", userMessage);
        ret.put("reply", reply);
        // 把对端关键信息回传给前端方便它直接 sendTextMsg
        JsonNode data = payload == null ? null : payload.get("data");
        if (data != null && data.isObject()) {
            if (data.hasNonNull("accountid")) ret.put("accountid", data.get("accountid").asLong());
            if (data.hasNonNull("fid"))       ret.put("fid",       data.get("fid").asLong());
            if (data.hasNonNull("nickname"))  ret.put("nickname",  data.get("nickname").asText());
            if (data.hasNonNull("msgid"))     ret.put("msgid",     data.get("msgid").asLong());
        }
        return R.ok(ret);
    }

    private String extractMessage(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            throw new ApiException(400, "请求体为空");
        }
        // {code:3, data:{ content:"..." }}
        JsonNode data = payload.get("data");
        if (data != null && data.isObject() && data.hasNonNull("content")) {
            String s = data.get("content").asText();
            if (!s.isBlank()) return s;
        }
        // {message:"..."}
        if (payload.hasNonNull("message")) {
            String s = payload.get("message").asText();
            if (!s.isBlank()) return s;
        }
        // {content:"..."}
        if (payload.hasNonNull("content")) {
            String s = payload.get("content").asText();
            if (!s.isBlank()) return s;
        }
        throw new ApiException(400, "请求体里没有 data.content / message / content 字段");
    }
}
