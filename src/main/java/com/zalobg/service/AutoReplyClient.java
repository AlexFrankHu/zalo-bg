package com.zalobg.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zalobg.common.ApiException;
import com.zalobg.config.AppProps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 "生成 AI 回复" 调用转发给独立的 auto-reply 微服务 (默认 http://127.0.0.1:8802).
 * 这个类是原 com.zalobg.service.AiReplyService 的替代品:
 * 原来的 OpenAI 直调 + system prompt + model 等配置全部搬到 auto-reply 工程里,
 * zalo-bg 不再持有 AI_API_KEY, 也不再关心 AI 厂商具体是谁.
 */
@Slf4j
@Service
public class AutoReplyClient {

    private final AppProps props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public AutoReplyClient(AppProps props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getAutoReply().getTimeoutMs()))
                .build();
    }

    /** 一轮历史消息. role: "user" (对方发来) / "assistant" (我方回复). */
    public record HistoryMessage(String role, String content) {}

    /** 带历史上下文的多轮回复. history 老→新, 不含本轮 userMessage. */
    public String generateReply(String userMessage, List<HistoryMessage> history) {
        return generateReply(userMessage, history, null, null, null);
    }

    /**
     * 带完整上下文的回复. 除历史消息外把好友昵称/性别/我方昵称一并透传给 auto-reply
     * 做审计日志. 这些字段当前不参与 AI prompt, 只在下游打 log, 将来个性化时再启用.
     *
     * @param friendNickname 好友昵称 (来自 zalo payload.list[0].nickname)
     * @param friendGed      好友性别 (zalo_friend.gender, 0/1/2)
     * @param myNickname     我方账号昵称 (前端 autoCollectAll 透传的 accountNickname)
     */
    public String generateReply(String userMessage, List<HistoryMessage> history,
                                String friendNickname, Integer friendGed, String myNickname) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new ApiException(400, "userMessage 不能为空");
        }

        List<Map<String, Object>> historyJson = new ArrayList<>();
        if (history != null) {
            for (HistoryMessage h : history) {
                if (h == null || h.content() == null || h.content().isBlank()) continue;
                String role = ("assistant".equals(h.role())) ? "assistant" : "user";
                historyJson.add(Map.of("role", role, "content", h.content()));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userMessage", userMessage);
        body.put("history", historyJson);
        // 可选审计字段 — 仅非 null 时才放入 body, null 字段不传 (下游按缺失处理)
        if (friendNickname != null) body.put("friendNickname", friendNickname);
        if (friendGed != null)      body.put("friendGed",      friendGed);
        if (myNickname != null)     body.put("myNickname",     myNickname);

        String reqBody;
        try {
            reqBody = json.writeValueAsString(body);
        } catch (Exception e) {
            throw new ApiException(500, "序列化 auto-reply 请求体失败: " + e.getMessage());
        }

        String url = props.getAutoReply().getBaseUrl() + "/api/auto-reply/generate";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getAutoReply().getTimeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("调 auto-reply 失败: {}", e.getMessage());
            throw new ApiException(502, "调 auto-reply 失败: " + e.getMessage());
        }

        if (resp.statusCode() / 100 != 2) {
            log.error("auto-reply 返回 {}: {}", resp.statusCode(), resp.body());
            throw new ApiException(502, "auto-reply 返回 " + resp.statusCode() + ": " + resp.body());
        }

        try {
            JsonNode root = json.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new ApiException(502,
                        "auto-reply 业务错误 code=" + code + ", msg=" + root.path("msg").asText());
            }
            JsonNode reply = root.path("data").path("reply");
            if (reply.isMissingNode() || reply.asText().isBlank()) {
                throw new ApiException(502, "auto-reply 响应里没有 reply: " + resp.body());
            }
            return reply.asText().trim();
        } catch (ApiException ae) {
            throw ae;
        } catch (Exception e) {
            throw new ApiException(502, "解析 auto-reply 响应失败: " + e.getMessage());
        }
    }
}
