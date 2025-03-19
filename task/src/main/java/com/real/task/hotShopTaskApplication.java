package com.real.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.real.common",
        "com.real.infrastructure",
        "com.real.domain",
        "com.real.task",
})
@MapperScan("com.real.domain.mapper")
public class hotShopTaskApplication {
    public static void main(String[] args) {
        SpringApplication.run(hotShopTaskApplication.class, args);
    }
}