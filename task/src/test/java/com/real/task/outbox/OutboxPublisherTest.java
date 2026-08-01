package com.real.task.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.domain.messaging.OutboxEvent;
import com.real.domain.messaging.OutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    private final OutboxMapper mapper = mock(OutboxMapper.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void executeTransactionCallbacks() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void correlatedAckWithoutReturnMarksCurrentLeasePublished() {
        OutboxEvent event = event("ORDER_CREATED", 1, "lease-a", 4);
        completeConfirm(true, false);
        when(mapper.published(event.outboxId(), event.leaseToken(), event.version(), now())).thenReturn(1);

        publisher(8, new OutboxPublishFailpoint() { }).publish(event);

        verify(mapper).published(event.outboxId(), event.leaseToken(), event.version(), now());
        verify(mapper, never()).recordFailure(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void nackNeverMarksPublishedAndSchedulesBoundedRetry() {
        OutboxEvent event = event("ORDER_CREATED", 1, "lease-a", 4);
        completeConfirm(false, false);
        when(mapper.recordFailure(anyLong(), anyString(), anyLong(), eq("NEW"), any(), eq("BROKER_NACK")))
                .thenReturn(1);

        publisher(8, new OutboxPublishFailpoint() { }).publish(event);

        verify(mapper, never()).published(anyLong(), anyString(), anyLong(), any());
        verify(mapper).recordFailure(event.outboxId(), event.leaseToken(), event.version(),
                "NEW", now().plusSeconds(1), "BROKER_NACK");
    }

    @Test
    void mandatoryReturnWinsEvenWhenBrokerConfirmIsAck() {
        OutboxEvent event = event("ORDER_CREATED", 1, "lease-a", 4);
        completeConfirm(true, true);
        when(mapper.recordFailure(anyLong(), anyString(), anyLong(), eq("NEW"), any(), eq("UNROUTABLE")))
                .thenReturn(1);

        publisher(8, new OutboxPublishFailpoint() { }).publish(event);

        verify(mapper, never()).published(anyLong(), anyString(), anyLong(), any());
        verify(mapper).recordFailure(event.outboxId(), event.leaseToken(), event.version(),
                "NEW", now().plusSeconds(1), "UNROUTABLE");
    }

    @Test
    void exhaustedAttemptBecomesTerminalFailed() {
        OutboxEvent event = event("ORDER_CREATED", 8, "lease-a", 4);
        completeConfirm(false, false);
        when(mapper.recordFailure(anyLong(), anyString(), anyLong(), eq("FAILED"), any(), eq("BROKER_NACK")))
                .thenReturn(1);

        publisher(8, new OutboxPublishFailpoint() { }).publish(event);

        verify(mapper).recordFailure(event.outboxId(), event.leaseToken(), event.version(),
                "FAILED", now().plusSeconds(128), "BROKER_NACK");
    }

    @Test
    void unknownEventTypeIsExplicitTerminalFailureWithoutNetworkCall() {
        OutboxEvent event = event("NOT_SUPPORTED", 1, "lease-a", 4);
        when(mapper.recordFailure(anyLong(), anyString(), anyLong(), eq("FAILED"), any(), eq("UNKNOWN_EVENT_TYPE")))
                .thenReturn(1);

        publisher(8, new OutboxPublishFailpoint() { }).publish(event);

        verifyNoInteractions(rabbit);
        verify(mapper).recordFailure(event.outboxId(), event.leaseToken(), event.version(),
                "FAILED", now().plusSeconds(1), "UNKNOWN_EVENT_TYPE");
    }

    @Test
    void crashAfterBrokerConfirmLeavesPublishingForLeaseRecovery() {
        OutboxEvent event = event("ORDER_CREATED", 1, "lease-a", 4);
        completeConfirm(true, false);
        OutboxPublishFailpoint crash = new OutboxPublishFailpoint() {
            @Override public void afterBrokerConfirm(OutboxEvent ignored) {
                throw new OutboxPublisherCrashException("after-confirm");
            }
        };

        assertThatThrownBy(() -> publisher(8, crash).publish(event))
                .isInstanceOf(OutboxPublisherCrashException.class);
        verify(mapper, never()).published(anyLong(), anyString(), anyLong(), any());
        verify(mapper, never()).recordFailure(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void lateConfirmCannotCrossFencingVersion() {
        OutboxEvent event = event("ORDER_CREATED", 1, "old-lease", 4);
        completeConfirm(true, false);
        when(mapper.published(anyLong(), anyString(), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> publisher(8, new OutboxPublishFailpoint() { }).publish(event))
                .isInstanceOf(OutboxLeaseLostException.class);
        verify(mapper).published(event.outboxId(), "old-lease", 4, now());
        verify(mapper, never()).recordFailure(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void confirmTimeoutUsesRetryableClassificationAndBackoff() {
        OutboxEvent event = event("ORDER_CREATED", 2, "lease-timeout", 5);
        when(mapper.recordFailure(anyLong(), anyString(), anyLong(), eq("NEW"), any(),
                eq("CONFIRM_TIMEOUT"))).thenReturn(1);

        publisher(8, Duration.ofMillis(1), new OutboxPublishFailpoint() { }).publish(event);

        verify(mapper).recordFailure(event.outboxId(), event.leaseToken(), event.version(),
                "NEW", now().plusSeconds(2), "CONFIRM_TIMEOUT");
        verify(mapper, never()).published(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void invalidPayloadAndInvalidRouteAreTerminalWithoutBrokerTraffic() {
        OutboxEvent malformed = new OutboxEvent(11, UUID.randomUUID().toString(), "ORDER", "order-1",
                "ORDER_CREATED", "not-json", "PUBLISHING", 3, 1, "lease-payload",
                now().plusSeconds(30), 6, now());
        when(mapper.recordFailure(anyLong(), anyString(), anyLong(), eq("FAILED"), any(),
                eq("INVALID_PAYLOAD"))).thenReturn(1);
        publisher(8, new OutboxPublishFailpoint() { }).publish(malformed);

        OutboxEvent invalidRoute = new OutboxEvent(12, "not-a-uuid", "ORDER", "order-1",
                "ORDER_CREATED", "{}", "PUBLISHING", 4, 1, "lease-route",
                now().plusSeconds(30), 7, now());
        when(mapper.recordFailure(eq(12L), anyString(), anyLong(), eq("FAILED"), any(),
                eq("INVALID_ROUTE"))).thenReturn(1);
        publisher(8, new OutboxPublishFailpoint() { }).publish(invalidRoute);

        verifyNoInteractions(rabbit);
        verify(mapper).recordFailure(eq(11L), eq("lease-payload"), eq(6L), eq("FAILED"),
                any(), eq("INVALID_PAYLOAD"));
        verify(mapper).recordFailure(eq(12L), eq("lease-route"), eq(7L), eq("FAILED"),
                any(), eq("INVALID_ROUTE"));
    }

    @Test
    void afterClaimCrashLeavesLeaseUntouched() {
        OutboxEvent event = event("ORDER_CREATED", 1, "lease-crash", 2);
        OutboxPublishFailpoint crash = new OutboxPublishFailpoint() {
            @Override public void afterClaim(OutboxEvent ignored) {
                throw new OutboxPublisherCrashException("after-claim");
            }
        };

        assertThatThrownBy(() -> publisher(8, crash).publish(event))
                .isInstanceOf(OutboxPublisherCrashException.class);
        verifyNoInteractions(rabbit);
        verify(mapper, never()).published(anyLong(), anyString(), anyLong(), any());
        verify(mapper, never()).recordFailure(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void expiredPublishingAtAttemptLimitBecomesFailedInsteadOfSticking() {
        OutboxEvent exhausted = new OutboxEvent(13, UUID.randomUUID().toString(), "ORDER", "order-1",
                "ORDER_CREATED", "{}", "PUBLISHING", 15, 8, "expired-lease",
                now().minusSeconds(1), 9, now());
        when(mapper.lockClaimable(50, now())).thenReturn(List.of(exhausted));
        when(mapper.failExpiredExhausted(13, "expired-lease", 9, now(), 8)).thenReturn(1);

        assertThat(publisher(8, new OutboxPublishFailpoint() { }).claimBatch()).isEmpty();

        verify(mapper).failExpiredExhausted(13, "expired-lease", 9, now(), 8);
        verify(mapper, never()).claim(anyLong(), anyLong(), anyString(), any(), any(), anyInt());
    }

    @Test
    void fencedFailureUpdateZeroIsReportedAsLeaseLost() {
        OutboxEvent event = event("ORDER_CREATED", 1, "stale-lease", 3);
        completeConfirm(false, false);
        when(mapper.recordFailure(anyLong(), anyString(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> publisher(8, new OutboxPublishFailpoint() { }).publish(event))
                .isInstanceOf(OutboxLeaseLostException.class);
    }

    @Test
    void publisherConfigurationRejectsNonPositiveAndUnsafeDurations() {
        assertThatThrownBy(() -> new OutboxPublisherProperties(true, 0, Duration.ofMillis(1),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofMinutes(5), 2, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxPublisherProperties(true, 50, Duration.ZERO,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofMinutes(5), 2, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxPublisherProperties(true, 50, Duration.ofMillis(1),
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofMinutes(5), 2, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxPublisherProperties(true, 50, Duration.ofMillis(1),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(1), 2, 8)).isInstanceOf(IllegalArgumentException.class);
    }

    private void completeConfirm(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        new Message(new byte[0], new MessageProperties()), 312,
                        "NO_ROUTE", "exchange", "key"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }

    private OutboxPublisher publisher(int maxAttempts, OutboxPublishFailpoint failpoint) {
        return publisher(maxAttempts, Duration.ofSeconds(5), failpoint);
    }

    private OutboxPublisher publisher(int maxAttempts, Duration confirmTimeout,
            OutboxPublishFailpoint failpoint) {
        OutboxPublisherProperties properties = new OutboxPublisherProperties(
                true, 50, Duration.ofMillis(250), Duration.ofSeconds(30),
                confirmTimeout, Duration.ofSeconds(1), Duration.ofMinutes(5), 2, maxAttempts);
        return new OutboxPublisher(mapper, rabbit, new ObjectMapper(), transactions,
                properties, failpoint, clock, UUID::randomUUID);
    }

    private OutboxEvent event(String type, int consecutiveAttempts, String lease, long version) {
        String eventId = UUID.randomUUID().toString();
        return new OutboxEvent(10, eventId, "ORDER", "order-1", type,
                "{\"schemaVersion\":1,\"orderId\":\"order-1\",\"expiresAtMs\":1785543300000}",
                "PUBLISHING", consecutiveAttempts, consecutiveAttempts, lease,
                now().plusSeconds(30), version, now().minusSeconds(1));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
