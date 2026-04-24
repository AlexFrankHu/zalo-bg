package com.zalobg;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.zalobg.mapper")
public class ZaloBgApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZaloBgApplication.class, args);
    }
}
