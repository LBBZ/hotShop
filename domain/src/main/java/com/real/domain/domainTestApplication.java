package com.real.domain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.real")
public class domainTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(domainTestApplication.class, args);
    }
}
