package com.zalobg.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zalobg.auth.JwtService;
import com.zalobg.common.ApiException;
import com.zalobg.common.R;
import com.zalobg.entity.AdminUser;
import com.zalobg.mapper.AdminUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "认证 (Auth)", description = "管理后台登录")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Operation(summary = "登录, 返回 JWT")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody @Valid LoginReq req) {
        AdminUser u = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUser>()
                        .eq(AdminUser::getUsername, req.getUsername())
                        .last("LIMIT 1"));
        if (u == null || u.getEnabled() == null || u.getEnabled() == 0) {
            throw new ApiException(401, "账号或密码错误");
        }
        if (!passwordEncoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new ApiException(401, "账号或密码错误");
        }
        String token = jwtService.issue(u.getId(), u.getUsername());
        return R.ok(Map.of(
                "token", token,
                "username", u.getUsername(),
                "nickName", u.getNickName() == null ? u.getUsername() : u.getNickName()
        ));
    }

    @Data
    public static class LoginReq {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }
}
