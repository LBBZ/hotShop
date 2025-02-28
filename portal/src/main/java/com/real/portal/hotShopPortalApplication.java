package com.real.portal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.real.portal",  // 当前模块
        "com.real.domain",  // 领域模块
        "com.real.common"   // 扫描通用模块（如全局异常处理）
})
@MapperScan("com.real.domain.mapper")
public class hotShopPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(hotShopPortalApplication.class, args);
    }

}
