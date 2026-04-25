package com.zalobg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "zalo-bg")
public class AppProps {

    private final Auth auth = new Auth();
    private final Admin admin = new Admin();
    private final Collect collect = new Collect();
    private final Ai ai = new Ai();

    @Data
    public static class Auth {
        private String jwtSecret;
        private int jwtTtlHours = 24;
    }

    @Data
    public static class Admin {
        private String username = "admin";
        private String password = "admin123";
    }

    @Data
    public static class Collect {
        private String token = "zalo-collect-token";
    }

    @Data
    public static class Ai {
        /** AI provider base URL (OpenAI 兼容). 默认 OpenAI; DeepSeek 改 https://api.deepseek.com 等. */
        private String baseUrl = "https://api.openai.com/v1";
        /** API key, injected via env var AI_API_KEY (兼容 OPENAI_API_KEY / PERPLEXITY_API_KEY). */
        private String apiKey;
        /** Model name. OpenAI: gpt-4o-mini; DeepSeek: deepseek-chat; Perplexity: sonar. */
        private String model = "gpt-4o-mini";
        /** System prompt that tells the AI how to behave when generating replies. */
        private String systemPrompt =
                "你是一个热爱电子游戏的玩家, 性格活泼。" +
                "请围绕'游戏'话题, 用对方使用的语言简短回复(<=30 字)。" +
                "回复风格亲切、自然, 不要使用 markdown 或表情符号。";
        /** Connection / read timeout in milliseconds for HTTP calls to Perplexity. */
        private int timeoutMs = 30_000;
    }
}
