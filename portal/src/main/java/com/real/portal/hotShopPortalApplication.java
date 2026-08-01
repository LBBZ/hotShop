package com.real.portal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;

@SpringBootApplication(exclude = RabbitAutoConfiguration.class, scanBasePackages = {
        "com.real.common",
        "com.real.infrastructure",
        "com.real.security",
        "com.real.domain",
        "com.real.portal",
})
@MapperScan({"com.real.domain.mapper", "com.real.domain.messaging"})
public class hotShopPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(hotShopPortalApplication.class, args);
    }

}
