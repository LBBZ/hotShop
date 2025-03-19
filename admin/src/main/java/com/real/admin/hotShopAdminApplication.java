package com.real.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.real.common",
        "com.real.infrastructure",
        "com.real.security",
        "com.real.domain",
        "com.real.admin",
})
@MapperScan("com.real.domain.mapper")
public class hotShopAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(hotShopAdminApplication.class, args);
    }

}
