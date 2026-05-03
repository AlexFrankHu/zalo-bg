package com.zalobg.auth;

import com.zalobg.config.AppProps;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppProps props;

    public JwtAuthFilter(JwtService jwtService, AppProps props) {
        this.jwtService = jwtService;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 采集接口走独立 token 校验 (X-Collect-Token; AI 自动回复走 /api/collect/messages 同一路径, 无独立 /api/ai 入口)
        if (path.startsWith("/api/collect/")) {
            String clientToken = request.getHeader("X-Collect-Token");
            if (!props.getCollect().getToken().equals(clientToken)) {
                response.setStatus(401);
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write("{\"code\":401,\"msg\":\"invalid collect token\"}");
                return;
            }
            // 把采集请求标记为 ROLE_COLLECT
            var auth = new UsernamePasswordAuthenticationToken(
                    "collector", null, List.of(new SimpleGrantedAuthority("ROLE_COLLECT")));
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
            return;
        }

        // 其它 /api/** 走 JWT
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                Long userId = Long.valueOf(claims.getSubject());
                String username = claims.get("username", String.class);
                var auth = new UsernamePasswordAuthenticationToken(
                        new AuthUser(userId, username),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // 让 Spring Security 自动走 401
            }
        }

        filterChain.doFilter(request, response);
    }

    public record AuthUser(Long userId, String username) {
    }
}
