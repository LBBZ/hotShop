package com.real.security.service;

import com.real.security.config.SecurityProperties;
import com.real.security.identity.SessionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientAddressResolverTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("ipv6LiteralCases")
    void enforcesEmbeddedIpv4PositionAndIpv6LiteralBoundaries(
            String input,
            String expected
    ) {
        assertThat(ClientAddressResolver.normalizeLiteral(input)).isEqualTo(expected);
    }

    private static Stream<Arguments> ipv6LiteralCases() {
        return Stream.of(
                Arguments.of("192.0.2.1::", null),
                Arguments.of("192.0.2.1::1", null),
                Arguments.of("1:2:3:4:5:6:192.0.2.1::", null),
                Arguments.of("1::2::3", null),
                Arguments.of("1:2:3:4:192.0.2.1:7", null),
                Arguments.of("::ffff:256.0.2.1", null),
                Arguments.of("1:2:3:4:5:6:7:10000", null),
                Arguments.of("1:2:3:4:5:6:7:8:9", null),
                Arguments.of("::ffff:192.0.2.1", "::ffff:c000:201"),
                Arguments.of(
                        "1:2:3:4:5:6:192.0.2.1",
                        "1:2:3:4:5:6:c000:201"
                ),
                Arguments.of("::", "::"),
                Arguments.of("::1", "::1"),
                Arguments.of("2001:0db8::1", "2001:db8::1"),
                Arguments.of(
                        "2001:0db8:0000:0001:0002:0003:0004:0005",
                        "2001:db8:0:1:2:3:4:5"
                )
        );
    }

    @Test
    void forwardingDisabledIgnoresMaliciousHeader() {
        ClientAddressResolver resolver = resolver(false, Set.of("10.0.0.2"));

        assertThat(resolve(resolver, "10.0.0.2", "localhost, 198.51.100.9"))
                .isEqualTo("10.0.0.2");
    }

    @Test
    void untrustedImmediatePeerIgnoresForwardedHeader() {
        ClientAddressResolver resolver = resolver(true, Set.of("10.0.0.2"));

        assertThat(resolve(resolver, "203.0.113.7", "198.51.100.9"))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void appendModeIgnoresSpoofedLeftmostValue() {
        ClientAddressResolver resolver = resolver(true, Set.of("10.0.0.2"));

        assertThat(resolve(
                resolver,
                "10.0.0.2",
                "192.0.2.200, 198.51.100.9"
        )).isEqualTo("198.51.100.9");
    }

    @Test
    void skipsOnlyContinuousTrustedProxyChainFromRight() {
        ClientAddressResolver resolver = resolver(
                true,
                Set.of("10.0.0.2", "2001:db8:0:0:0:0:0:2")
        );

        assertThat(resolve(
                resolver,
                "10.0.0.2",
                "192.0.2.200, 2001:db8::99, 2001:0db8:0:0:0:0:0:2"
        )).isEqualTo("2001:db8::99");
    }

    @Test
    void malformedEmptyHostnameOverlongAndExcessiveChainsFallBackToImmediatePeer() {
        ClientAddressResolver resolver = resolver(true, Set.of("10.0.0.2"));
        String tooLong = "1".repeat(ClientAddressResolver.MAX_FORWARDED_HEADER_LENGTH + 1);
        String tooMany = String.join(
                ",",
                java.util.Collections.nCopies(
                        ClientAddressResolver.MAX_FORWARDED_HOPS + 1,
                        "198.51.100.9"
                )
        );

        for (String rejected : List.of(
                "",
                " ",
                "localhost",
                "proxy.example.test",
                "198.51.100.1,,198.51.100.9",
                "999.51.100.9",
                "2001:db8:::9",
                tooLong,
                tooMany
        )) {
            assertThat(resolve(resolver, "10.0.0.2", rejected))
                    .as("header %s", rejected)
                    .isEqualTo("10.0.0.2");
        }
    }

    @Test
    void nonLiteralImmediatePeerNeverEnablesForwardedTrust() {
        ClientAddressResolver resolver = resolver(true, Set.of("10.0.0.2"));

        assertThat(resolve(resolver, "proxy.example.test", "198.51.100.9"))
                .isEqualTo("unknown");
    }

    @Test
    void multipleForwardedHeaderLinesAreRejectedAsOneMalformedChain() {
        ClientAddressResolver resolver = resolver(true, Set.of("10.0.0.2"));
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        request.addHeader("X-Forwarded-For", "192.0.2.1");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.2");
    }

    @Test
    void normalizesIpv4AndIpv6LiteralsWithoutDnsResolution() {
        assertThat(ClientAddressResolver.normalizeLiteral("192.168.001.010"))
                .isEqualTo("192.168.1.10");
        assertThat(ClientAddressResolver.normalizeLiteral("2001:0DB8:0:0:0:0:0:1"))
                .isEqualTo("2001:db8::1");
        assertThat(ClientAddressResolver.normalizeLiteral("::ffff:192.0.2.1"))
                .isEqualTo("::ffff:c000:201");
        assertThat(ClientAddressResolver.normalizeLiteral("localhost")).isNull();
        assertThat(ClientAddressResolver.normalizeLiteral("example.test")).isNull();
    }

    @Test
    void invalidTrustedProxyConfigurationFailsClosedAtStartup() {
        assertThatThrownBy(() -> resolver(true, Set.of("proxy.example.test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Trusted proxy addresses must be IPv4 or IPv6 literals");
        assertThatThrownBy(() -> resolver(true, Set.of("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Trusted proxy addresses must be IPv4 or IPv6 literals");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rotatingSpoofedLeftmostValuesProducesTheSameRateLimitAddressKey() {
        ClientAddressResolver resolver = resolver(true, Set.of("10.0.0.2"));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyString()))
                .thenReturn((List) List.of(1L, 60L));
        AuthenticationRateLimiter limiter =
                new AuthenticationRateLimiter(redisTemplate, resolver, new SecurityProperties());
        MockHttpServletRequest first = request("10.0.0.2");
        first.addHeader("X-Forwarded-For", "192.0.2.10, 198.51.100.9");
        MockHttpServletRequest second = request("10.0.0.2");
        second.addHeader("X-Forwarded-For", "192.0.2.11, 198.51.100.9");

        limiter.beforeRefresh(SessionType.USER, first);
        limiter.beforeRefresh(SessionType.USER, second);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2)).execute(any(), keys.capture(), anyString());
        assertThat(keys.getAllValues().get(0))
                .isEqualTo(keys.getAllValues().get(1))
                .containsExactly(
                        "hotshop:auth:rate:user-refresh:ip:"
                                + AuthenticationRateLimiter.hash("198.51.100.9")
                );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void everyAuthenticationRateLimitEntryPointUsesTheSameResolver() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ClientAddressResolver resolver = mock(ClientAddressResolver.class);
        SecurityProperties properties = new SecurityProperties();
        MockHttpServletRequest request = request("10.0.0.2");
        when(resolver.resolve(request)).thenReturn("198.51.100.9");
        when(redisTemplate.execute(any(), anyList(), anyString()))
                .thenReturn((List) List.of(1L, 60L));
        AuthenticationRateLimiter limiter =
                new AuthenticationRateLimiter(redisTemplate, resolver, properties);

        limiter.beforeLogin(SessionType.USER, request, "user");
        limiter.recordLoginFailure(SessionType.USER, request, "user");
        limiter.beforeRefresh(SessionType.USER, request);
        limiter.beforeLogin(SessionType.ADMIN, request, "admin");
        limiter.recordLoginFailure(SessionType.ADMIN, request, "admin");
        limiter.beforeRefresh(SessionType.ADMIN, request);
        limiter.beforeAgentExchange(request);

        verify(resolver, times(7)).resolve(request);
    }

    private ClientAddressResolver resolver(boolean enabled, Set<String> trusted) {
        SecurityProperties properties = new SecurityProperties();
        properties.getRateLimit().setTrustForwardedHeaders(enabled);
        properties.getRateLimit().setTrustedProxyAddresses(trusted);
        return new ClientAddressResolver(properties);
    }

    private String resolve(
            ClientAddressResolver resolver,
            String remoteAddress,
            String forwarded
    ) {
        MockHttpServletRequest request = request(remoteAddress);
        request.addHeader("X-Forwarded-For", forwarded);
        return resolver.resolve(request);
    }

    private MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
