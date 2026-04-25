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
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 调用 OpenAI 兼容的 chat/completions 接口生成回复内容.
 * 默认对接 OpenAI (gpt-4o-mini), 也可通过 base-url + model 切到 DeepSeek / Qwen / Perplexity 等.
 */
@Slf4j
@Service
public class AiReplyService {

    private final AppProps props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public AiReplyService(AppProps props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getAi().getTimeoutMs()))
                .build();
    }

    /**
     * 让 AI 围绕"游戏"主题为指定输入生成一条简短回复.
     *
     * @param userMessage 对方发来的原始文本
     * @return AI 生成的回复内容(纯文本, 已去掉首尾空白)
     */
    /** 没配 AI_API_KEY 时, 用这个固定回复占位 (接入真 AI 后自动切换). */
    private static final String FALLBACK_REPLY = "你好";

    public String generateReply(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new ApiException(400, "userMessage 不能为空");
        }
        String apiKey = props.getAi().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("AI_API_KEY 未配置, 返回固定回复 '{}'", FALLBACK_REPLY);
            return FALLBACK_REPLY;
        }

        Map<String, Object> body = Map.of(
                "model", props.getAi().getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", props.getAi().getSystemPrompt()),
                        Map.of("role", "user",   "content", userMessage)
                ),
                "max_tokens", 200,
                "temperature", 0.7
        );

        String reqBody;
        try {
            reqBody = json.writeValueAsString(body);
        } catch (Exception e) {
            throw new ApiException(500, "序列化请求体失败: " + e.getMessage());
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getAi().getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofMillis(props.getAi().getTimeoutMs()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("AI 调用失败: {}", e.getMessage());
            throw new ApiException(502, "调用 AI 失败: " + e.getMessage());
        }

        if (resp.statusCode() / 100 != 2) {
            log.error("AI 返回 {}: {}", resp.statusCode(), resp.body());
            throw new ApiException(502, "AI 返回 " + resp.statusCode() + ": " + resp.body());
        }

        try {
            JsonNode root = json.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new ApiException(502, "AI 响应里没有 content: " + resp.body());
            }
            return content.asText().trim();
        } catch (ApiException ae) {
            throw ae;
        } catch (Exception e) {
            throw new ApiException(502, "解析 AI 响应失败: " + e.getMessage());
        }
    }
}
