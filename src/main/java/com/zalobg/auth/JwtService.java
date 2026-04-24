package com.zalobg.auth;

import com.zalobg.common.ApiException;
import com.zalobg.config.AppProps;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(AppProps props) {
        byte[] secret = props.getAuth().getJwtSecret().getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttlMillis = props.getAuth().getJwtTtlHours() * 3600_000L;
    }

    public String issue(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new ApiException(401, "登录已过期, 请重新登录");
        } catch (Exception e) {
            throw new ApiException(401, "无效的登录凭证");
        }
    }
}
