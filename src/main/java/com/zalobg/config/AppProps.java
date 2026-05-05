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
    private final AutoReply autoReply = new AutoReply();

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

    /**
     * 调独立的 auto-reply 微服务 (默认 http://127.0.0.1:8802) 生成 AI 回复.
     * 原来 zalo-bg 直接调 OpenAI 的 AI 子结构 (AppProps.Ai) 已迁移到 auto-reply 工程.
     */
    @Data
    public static class AutoReply {
        /** auto-reply 服务 base URL. 同机部署走 127.0.0.1:8802. */
        private String baseUrl = "http://127.0.0.1:8802";
        /** HTTP 连接 / 读超时 (ms). 透传给 HttpClient. */
        private int timeoutMs = 30_000;
        /** 从 zalo_message 拉历史聊天作为上下文时的最近 N 条数. */
        private int historyLimit = 30;
    }
}
