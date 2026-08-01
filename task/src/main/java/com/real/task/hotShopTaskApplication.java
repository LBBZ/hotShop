package com.real.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.real.task.seckill.SeckillOrderProperties;
import com.real.task.outbox.OutboxPublisherProperties;

@SpringBootApplication(scanBasePackages = {
        "com.real.common",
        "com.real.infrastructure",
        "com.real.domain",
        "com.real.task",
})
@MapperScan({"com.real.domain.mapper", "com.real.domain.messaging"})
@EnableScheduling
@EnableConfigurationProperties({SeckillOrderProperties.class, OutboxPublisherProperties.class})
public class hotShopTaskApplication {
    public static void main(String[] args) {
        SpringApplication.run(hotShopTaskApplication.class, args);
    }
}
