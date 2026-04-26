package com.zalobg.controller;

import com.zalobg.common.R;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "健康检查")
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of(
                "service", "zalo-bg",
                "ts", System.currentTimeMillis()
        ));
    }
}
