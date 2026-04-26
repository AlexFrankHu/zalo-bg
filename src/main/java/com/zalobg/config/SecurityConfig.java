package com.zalobg.config;

import com.zalobg.auth.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(c -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/", "/index.html", "/favicon.ico", "/static/**", "/assets/**",
                                "/css/**", "/js/**", "/img/**",
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/api/auth/login", "/api/health"
                        ).permitAll()
                        .requestMatchers("/api/collect/**").hasRole("COLLECT")
                        .requestMatchers("/api/ai/**").hasRole("COLLECT")
                        .requestMatchers("/api/admin/**").hasRole("USER")
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, resp, ex) -> {
                            resp.setStatus(401);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=utf-8");
                            resp.getWriter().write("{\"code\":401,\"msg\":\"请先登录\"}");
                        })
                        .accessDeniedHandler((req, resp, ex) -> {
                            resp.setStatus(403);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=utf-8");
                            resp.getWriter().write("{\"code\":403,\"msg\":\"无权限\"}");
                        })
                );
        return http.build();
    }
}
