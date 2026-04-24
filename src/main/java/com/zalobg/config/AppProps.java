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
}
