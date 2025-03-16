package com.real.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.real.task",  // 当前模块
        "com.real.domain", // 领域模块
        "com.real.common", // 扫描通用模块（如全局异常处理）
})
@MapperScan("com.real.domain.mapper")
public class hotShopTaskApplication {
    public static void main(String[] args) {
        SpringApplication.run(hotShopTaskApplication.class, args);
    }
}