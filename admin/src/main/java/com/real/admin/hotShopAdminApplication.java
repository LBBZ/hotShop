package com.real.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.real.admin",  // 当前模块
        "com.real.domain", // 领域模块
        "com.real.common", // 扫描通用模块（如全局异常处理）
        "com.real.portal"
})
@MapperScan("com.real.domain.mapper")
public class hotShopAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(hotShopAdminApplication.class, args);
    }

}
