package com.real.task.seckill;

import com.real.infrastructure.redis.RedisConnectionsConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SeckillOrderPollingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PollingConfiguration.class);

    @Test
    void defaultsStartWithOneSecondBlockingReads() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ReservationStreamConsumer.class);
            assertThat(context.getBean(SeckillOrderProperties.class).getReadBlock())
                    .isEqualTo(Duration.ofSeconds(1));
        });
    }

    @Test
    void longerBlockingReadStartsWithMatchingClientTimeout() {
        contextRunner.withPropertyValues(
                "hotshop.seckill.order-consumer.read-block=2s",
                "hotshop.redis.seckill.timeout=3s"
        ).run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(ReservationStreamConsumer.class));
    }

    @ParameterizedTest
    @CsvSource({"2s,2s", "3s,2s", "1s,1500ms", "1s,500ms"})
    void unsafeTimeoutCombinationFailsBeforePolling(String readBlock, String commandTimeout) {
        contextRunner.withPropertyValues(
                "hotshop.seckill.order-consumer.read-block=" + readBlock,
                "hotshop.redis.seckill.timeout=" + commandTimeout
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("HOTSHOP_SECKILL_ORDER_READ_BLOCK")
                    .hasMessageContaining("HOTSHOP_REDIS_SECKILL_TIMEOUT")
                    .hasMessageContaining("1s");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1ms", "500us"})
    void rejectsValuesThatCannotProduceAPositiveRedisBlock(String readBlock) {
        contextRunner.withPropertyValues("hotshop.seckill.order-consumer.read-block=" + readBlock)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void disabledConsumerDoesNotEnforceAnUnusedBlockingBudget() {
        contextRunner.withPropertyValues(
                "hotshop.seckill.order-consumer.enabled=false",
                "hotshop.seckill.order-consumer.read-block=2s",
                "hotshop.redis.seckill.timeout=2s"
        ).run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(ReservationStreamConsumer.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RedisConnectionsConfiguration.class, ReservationStreamConsumer.class})
    @EnableConfigurationProperties(SeckillOrderProperties.class)
    static class PollingConfiguration {
        @Bean
        SeckillRedisReservationGateway reservationGateway() {
            return mock(SeckillRedisReservationGateway.class);
        }

        @Bean
        SeckillProcessingService processingService() {
            return mock(SeckillProcessingService.class);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SeckillOrderMetrics metrics(SimpleMeterRegistry registry) {
            return new SeckillOrderMetrics(registry);
        }

        @Bean
        Tracer tracer() {
            return Tracer.NOOP;
        }
    }
}
