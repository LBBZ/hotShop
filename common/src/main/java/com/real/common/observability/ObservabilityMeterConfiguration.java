package com.real.common.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class ObservabilityMeterConfiguration {
    private static final Set<String> FORBIDDEN_TAGS = Set.of(
            "requestId", "request_id", "traceId", "trace_id", "spanId", "span_id",
            "userId", "user_id", "orderId", "order_id", "reservationId", "reservation_id"
    );

    @Bean
    MeterFilter rejectHighCardinalityBusinessTags() {
        return MeterFilter.deny(id -> id.getTags().stream()
                .anyMatch(tag -> FORBIDDEN_TAGS.contains(tag.getKey())));
    }
}
