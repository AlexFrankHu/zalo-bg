package com.zalobg.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zalobg.entity.AdminUser;
import com.zalobg.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitRunner implements CommandLineRunner {

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final AppProps props;

    @Override
    public void run(String... args) {
        String username = props.getAdmin().getUsername();
        String plain = props.getAdmin().getPassword();
        AdminUser exist = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUser>()
                        .eq(AdminUser::getUsername, username)
                        .last("LIMIT 1"));
        if (exist == null) {
            AdminUser u = new AdminUser();
            u.setUsername(username);
            u.setPasswordHash(passwordEncoder.encode(plain));
            u.setNickName("Administrator");
            u.setEnabled(1);
            adminUserMapper.insert(u);
            log.info("initialized admin user: {}", username);
        } else {
            log.info("admin user already exists: {}", username);
        }
    }
}
