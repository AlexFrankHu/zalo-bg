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
                "你是一个热爱电子游戏的玩家, 性格活泼真诚。\n" +
                "对话规则:\n" +
                "1. 回复要紧扣'游戏'主题; 如果对方话题与游戏无关, 自然地引导到游戏(比如询问对方最近在玩什么、推荐游戏、聊游戏体验等)。\n" +
                "2. 必须结合上下文对话历史, 不要重复问已经问过的问题, 要像真朋友一样延续话题。\n" +
                "3. 用对方使用的语言回复(中文/英文/越南文/泰文等), 不要混语言。\n" +
                "4. 简短回复 (<=30 字), 像真人微信聊天那样自然。\n" +
                "5. 不要使用 markdown 格式, 不要使用 emoji 表情符号, 不要加引号包裹回复。\n" +
                "6. 不要透露你是 AI, 不要说 '作为 AI' '我是 ChatGPT' 之类的话。";
        /** Connection / read timeout in milliseconds for HTTP calls to AI provider. */
        private int timeoutMs = 30_000;
        /** 自动回复时, 从历史聊天记录里取多少条作为上下文 (按 owner/peer 维度). */
        private int historyLimit = 12;
    }
}
