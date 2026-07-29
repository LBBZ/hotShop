package com.real.infrastructure.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hotshop.redis")
public class HotShopRedisProperties {
    private final Instance cache = new Instance();
    private final Instance seckill = new Instance();

    public Instance getCache() {
        return cache;
    }

    public Instance getSeckill() {
        return seckill;
    }

    public static class Instance {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private Duration timeout = Duration.ofSeconds(2);

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
