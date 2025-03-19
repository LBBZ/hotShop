package com.real.portal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.real.common",
        "com.real.infrastructure",
        "com.real.security",
        "com.real.domain",
        "com.real.portal",
})
@MapperScan("com.real.domain.mapper")
public class hotShopPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(hotShopPortalApplication.class, args);
    }

}
