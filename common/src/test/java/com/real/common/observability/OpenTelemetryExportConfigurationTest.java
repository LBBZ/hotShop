package com.real.common.observability;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryExportConfigurationTest {
    @Test
    void exportsTracesAndRetainsPrometheusWithoutOtlpMetricsOrLogs() throws Exception {
        BlockingQueue<ExportRequest> requests = new LinkedBlockingQueue<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            try {
                requests.add(new ExportRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestBody().readAllBytes()));
                // An empty protobuf ExportTraceServiceResponse is a successful OTLP response.
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        receiver.start();
        try {
            String endpoint = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/collector-test/v1/traces";
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                            MicrometerTracingAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, OtlpTracingAutoConfiguration.class,
                            MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class,
                            OtlpMetricsExportAutoConfiguration.class, OpenTelemetryLoggingAutoConfiguration.class,
                            OtlpLoggingAutoConfiguration.class))
                    .withPropertyValues("spring.application.name=hotshop-tracing-contract",
                            "management.tracing.sampling.probability=1.0",
                            "management.opentelemetry.tracing.export.otlp.endpoint=" + endpoint,
                            "management.opentelemetry.tracing.export.otlp.timeout=5s",
                            "management.otlp.metrics.export.enabled=false",
                            "management.logging.export.otlp.enabled=false",
                            // Supplying local endpoints proves the switches disable otherwise eligible exporters.
                            "management.otlp.metrics.export.url=" + endpoint.replace("traces", "metrics"),
                            "management.opentelemetry.logging.export.otlp.endpoint=" + endpoint.replace("traces", "logs"))
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(Tracer.class)
                                .hasSingleBean(OtlpHttpSpanExporter.class).hasSingleBean(SdkTracerProvider.class)
                                .hasSingleBean(PrometheusMeterRegistry.class)
                                .doesNotHaveBean(OtlpMeterRegistry.class)
                                .doesNotHaveBean(OtlpHttpLogRecordExporter.class);
                        PrometheusMeterRegistry metrics = context.getBean(PrometheusMeterRegistry.class);
                        metrics.counter("hotshop.boot4.tracing.contract").increment();
                        assertThat(metrics.scrape()).contains("hotshop_boot4_tracing_contract_total 1.0");
                        Tracer tracer = context.getBean(Tracer.class);
                        assertThat(tracer).isInstanceOf(OtelTracer.class);
                        Observation observation = Observation.start("hotshop.boot4.export",
                                context.getBean(ObservationRegistry.class));
                        String traceId;
                        String spanId;
                        try (Observation.Scope ignored = observation.openScope()) {
                            Span span = tracer.currentSpan();
                            assertThat(span).as("an observed operation must create a real tracing span").isNotNull();
                            traceId = span.context().traceId();
                            spanId = span.context().spanId();
                            assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
                            assertThat(spanId).matches("[0-9a-f]{16}").isNotEqualTo("0".repeat(16));
                        } finally {
                            observation.stop();
                        }

                        CompletableResultCode exported = context.getBean(SdkTracerProvider.class).forceFlush();
                        exported.join(10, TimeUnit.SECONDS);
                        assertThat(exported.isSuccess()).as("the OTLP batch must reach the local receiver").isTrue();
                        ExportRequest request = requests.poll(2, TimeUnit.SECONDS);
                        assertThat(request).as("an ended observation must be exported over HTTP").isNotNull();
                        assertThat(request.method()).isEqualTo("POST");
                        assertThat(request.path()).isEqualTo("/collector-test/v1/traces");
                        assertThat(request.contentType()).isEqualTo("application/x-protobuf");
                        // OTLP encodes IDs as raw byte fields, not their hexadecimal display strings.
                        // Matching the generated IDs proves the observed span reached the configured endpoint.
                        assertThat(request.body()).containsSequence(HexFormat.of().parseHex(traceId))
                                .containsSequence(HexFormat.of().parseHex(spanId))
                                .containsSequence("hotshop.boot4.export".getBytes(StandardCharsets.UTF_8))
                                .containsSequence("hotshop-tracing-contract".getBytes(StandardCharsets.UTF_8));
                    });
        } finally {
            receiver.stop(0);
        }
    }

    private record ExportRequest(String method, String path, String contentType, byte[] body) { }
}
