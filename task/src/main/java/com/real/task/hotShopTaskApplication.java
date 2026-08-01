package com.real.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.real.task.seckill.SeckillOrderProperties;

@SpringBootApplication(scanBasePackages = {
        "com.real.common",
        "com.real.infrastructure",
        "com.real.domain",
        "com.real.task",
})
@MapperScan("com.real.domain.mapper")
@EnableScheduling
@EnableConfigurationProperties(SeckillOrderProperties.class)
public class hotShopTaskApplication {
    public static void main(String[] args) {
        SpringApplication.run(hotShopTaskApplication.class, args);
    }
}
