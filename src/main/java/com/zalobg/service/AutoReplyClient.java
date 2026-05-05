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

    /**
     * 一轮历史消息. role: "user" (对方发来) / "assistant" (我方回复).
     * time: 消息时间 "yyyy-MM-dd HH:mm:ss" (来源 zalo_message.gmt_create);
     * 历史数据缺失 gmt_create 时 null. 下游 auto-reply 仅用于审计日志,
     * 不参与 prompt.
     */
    public record HistoryMessage(String role, String content, String time) {}

    /** 带历史上下文的多轮回复. history 老→新, 不含本轮 userMessage. */
    public String generateReply(String userMessage, List<HistoryMessage> history) {
        return generateReply(userMessage, history, null, null, null, null, null);
    }

    /** 兼容老调用 (3 个审计字段, 没有 myGed/state). 内部转调全参版本传 null. */
    public String generateReply(String userMessage, List<HistoryMessage> history,
                                String friendNickname, Integer friendGed, String myNickname) {
        return generateReply(userMessage, history, friendNickname, friendGed, myNickname, null, null);
    }

    /**
     * 带完整上下文的回复. 除历史消息外把好友昵称/性别/我方昵称/我方性别/触发场景 state
     * 一并透传给 auto-reply 做审计日志. 这些字段当前不参与 AI prompt, 只在下游打 log,
     * 将来个性化时再启用.
     *
     * @param friendNickname 好友昵称 (来自 zalo payload.list[0].nickname)
     * @param friendGed      好友性别 (zalo_friend.gender, 0/1/2)
     * @param myNickname     我方账号昵称 (前端 autoCollectAll 透传的 accountNickname)
     * @param myGed          我方性别 (zalo_account.sex)
     * @param state          触发场景 (0=被动回复, 1=新好友15分钟未发, 2-5=主动N次未回, 6-8=久未跟进)
     */
    public String generateReply(String userMessage, List<HistoryMessage> history,
                                String friendNickname, Integer friendGed, String myNickname,
                                Integer myGed, Integer state) {
        // userMessage 允许为空 (state>=1 主动跟进场景下没有"本轮用户消息"). 上游
        // AutoReplyService 已经按 state 自己分流, 这里不再二次校验; 下游 auto-reply
        // 微服务里 AiReplyService 看到空 userMessage 会跳过本轮 user 消息, 让模型
        // 基于 system prompt + history 自己造话. null 统一规整成空串再透传.
        String safeUserMessage = (userMessage == null) ? "" : userMessage;

        List<Map<String, Object>> historyJson = new ArrayList<>();
        if (history != null) {
            for (HistoryMessage h : history) {
                if (h == null || h.content() == null || h.content().isBlank()) continue;
                String role = ("assistant".equals(h.role())) ? "assistant" : "user";
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", role);
                item.put("content", h.content());
                if (h.time() != null) item.put("time", h.time());
                historyJson.add(item);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userMessage", safeUserMessage);
        body.put("history", historyJson);
        // 可选审计字段 — 仅非 null 时才放入 body, null 字段不传 (下游按缺失处理)
        if (friendNickname != null) body.put("friendNickname", friendNickname);
        if (friendGed != null)      body.put("friendGed",      friendGed);
        if (myNickname != null)     body.put("myNickname",     myNickname);
        if (myGed != null)          body.put("myGed",          myGed);
        if (state != null)          body.put("state",          state);

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
