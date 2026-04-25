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
        /** Perplexity API base URL. */
        private String baseUrl = "https://api.perplexity.ai";
        /** Perplexity API key, injected via env var PERPLEXITY_API_KEY. */
        private String apiKey;
        /** Perplexity model name (sonar / sonar-pro / sonar-reasoning). */
        private String model = "sonar";
        /** System prompt that tells the AI how to behave when generating replies. */
        private String systemPrompt =
                "你是一个热爱电子游戏的玩家, 性格活泼。" +
                "请围绕'游戏'话题, 用对方使用的语言简短回复(<=30 字)。" +
                "回复风格亲切、自然, 不要使用 markdown 或表情符号。";
        /** Connection / read timeout in milliseconds for HTTP calls to Perplexity. */
        private int timeoutMs = 30_000;
    }
}
