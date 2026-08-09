package com.real.security.config;

import com.real.security.identity.IdentityType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "hotshop.security")
public class SecurityProperties {
    @Min(0)
    @Max(60)
    private long clockSkewSeconds = 30;
    @Valid
    private JwtDomain user = JwtDomain.userDefaults();
    @Valid
    private JwtDomain administrator = JwtDomain.adminDefaults();
    @Valid
    private JwtDomain agentDelegation = JwtDomain.agentDefaults();
    @Valid
    private ClientAssertion clientAssertion = new ClientAssertion();
    @Valid
    private Refresh refresh = new Refresh();
    @Valid
    private RateLimit rateLimit = new RateLimit();

    public JwtDomain domain(IdentityType identityType) {
        return switch (identityType) {
            case USER_ACCESS -> user;
            case ADMINISTRATOR_ACCESS -> administrator;
            case AGENT_DELEGATION -> agentDelegation;
        };
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public JwtDomain getUser() {
        return user;
    }

    public void setUser(JwtDomain user) {
        this.user = user;
    }

    public JwtDomain getAdministrator() {
        return administrator;
    }

    public void setAdministrator(JwtDomain administrator) {
        this.administrator = administrator;
    }

    public JwtDomain getAgentDelegation() {
        return agentDelegation;
    }

    public void setAgentDelegation(JwtDomain agentDelegation) {
        this.agentDelegation = agentDelegation;
    }

    public ClientAssertion getClientAssertion() {
        return clientAssertion;
    }

    public void setClientAssertion(ClientAssertion clientAssertion) {
        this.clientAssertion = clientAssertion;
    }

    public Refresh getRefresh() {
        return refresh;
    }

    public void setRefresh(Refresh refresh) {
        this.refresh = refresh;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public static class JwtDomain {
        @NotBlank
        private String issuer;
        @NotBlank
        private String audience;
        @NotBlank
        private String type;
        @NotBlank
        private String tokenUse;
        @NotBlank
        private String activeKid;
        private String privateKeyPath = "";
        private Map<String, String> verificationKeyPaths = new LinkedHashMap<>();
        @Min(60)
        @Max(900)
        private long ttlSeconds;

        private static JwtDomain userDefaults() {
            return defaults(
                    "https://auth.hotshop.local/user",
                    "hotshop-portal-api",
                    "user-access+jwt",
                    "user_access",
                    "user-local-1",
                    900
            );
        }

        private static JwtDomain adminDefaults() {
            return defaults(
                    "https://auth.hotshop.local/administrator",
                    "hotshop-admin-api",
                    "administrator-access+jwt",
                    "administrator_access",
                    "administrator-local-1",
                    900
            );
        }

        private static JwtDomain agentDefaults() {
            return defaults(
                    "https://auth.hotshop.local/agent-delegation",
                    "hotshop-agent-api",
                    "agent-delegation+jwt",
                    "agent_delegation",
                    "agent-delegation-local-1",
                    300
            );
        }

        private static JwtDomain defaults(
                String issuer,
                String audience,
                String type,
                String tokenUse,
                String activeKid,
                long ttlSeconds
        ) {
            JwtDomain domain = new JwtDomain();
            domain.issuer = issuer;
            domain.audience = audience;
            domain.type = type;
            domain.tokenUse = tokenUse;
            domain.activeKid = activeKid;
            domain.ttlSeconds = ttlSeconds;
            return domain;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTokenUse() {
            return tokenUse;
        }

        public void setTokenUse(String tokenUse) {
            this.tokenUse = tokenUse;
        }

        public String getActiveKid() {
            return activeKid;
        }

        public void setActiveKid(String activeKid) {
            this.activeKid = activeKid;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public Map<String, String> getVerificationKeyPaths() {
            return verificationKeyPaths;
        }

        public void setVerificationKeyPaths(Map<String, String> verificationKeyPaths) {
            this.verificationKeyPaths = verificationKeyPaths;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class ClientAssertion {
        @NotBlank
        private String issuer = "https://agent.hotshop.local/service";
        @NotBlank
        private String audience = "hotshop-agent-token-exchange";
        @NotBlank
        private String type = "client-auth+jwt";
        @NotBlank
        private String clientId = "hotshop-agent-service";
        private Map<String, String> verificationKeyPaths = new LinkedHashMap<>();
        @Min(10)
        @Max(120)
        private long maxTtlSeconds = 60;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public Map<String, String> getVerificationKeyPaths() {
            return verificationKeyPaths;
        }

        public void setVerificationKeyPaths(Map<String, String> verificationKeyPaths) {
            this.verificationKeyPaths = verificationKeyPaths;
        }

        public long getMaxTtlSeconds() {
            return maxTtlSeconds;
        }

        public void setMaxTtlSeconds(long maxTtlSeconds) {
            this.maxTtlSeconds = maxTtlSeconds;
        }
    }

    public static class Refresh {
        private boolean secureCookie = true;
        @Min(3600)
        private long ttlSeconds = Duration.ofDays(7).toSeconds();

        public boolean isSecureCookie() {
            return secureCookie;
        }

        public void setSecureCookie(boolean secureCookie) {
            this.secureCookie = secureCookie;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class RateLimit {
        private boolean trustForwardedHeaders;
        private Set<String> trustedProxyAddresses = new LinkedHashSet<>();
        @Valid
        private Policy userLoginIp = new Policy(20, 60);
        @Valid
        private Policy userLoginIdentity = new Policy(10, 300);
        @Valid
        private Policy userLoginFailure = new Policy(5, 900);
        @Valid
        private Policy administratorLoginIp = new Policy(10, 60);
        @Valid
        private Policy administratorLoginIdentity = new Policy(6, 300);
        @Valid
        private Policy administratorLoginFailure = new Policy(4, 1800);
        @Valid
        private Policy userRefresh = new Policy(30, 60);
        @Valid
        private Policy administratorRefresh = new Policy(20, 60);
        @Valid
        private Policy agentExchange = new Policy(20, 60);
        @Valid
        private Policy userOrderTransaction = new Policy(8, 60);
        @Valid
        private Policy userReservationTransaction = new Policy(8, 60);

        public boolean isTrustForwardedHeaders() {
            return trustForwardedHeaders;
        }

        public void setTrustForwardedHeaders(boolean trustForwardedHeaders) {
            this.trustForwardedHeaders = trustForwardedHeaders;
        }

        public Set<String> getTrustedProxyAddresses() {
            return trustedProxyAddresses;
        }

        public void setTrustedProxyAddresses(Set<String> trustedProxyAddresses) {
            this.trustedProxyAddresses = trustedProxyAddresses;
        }

        public Policy getUserLoginIp() {
            return userLoginIp;
        }

        public void setUserLoginIp(Policy userLoginIp) {
            this.userLoginIp = userLoginIp;
        }

        public Policy getUserLoginIdentity() {
            return userLoginIdentity;
        }

        public void setUserLoginIdentity(Policy userLoginIdentity) {
            this.userLoginIdentity = userLoginIdentity;
        }

        public Policy getUserLoginFailure() {
            return userLoginFailure;
        }

        public void setUserLoginFailure(Policy userLoginFailure) {
            this.userLoginFailure = userLoginFailure;
        }

        public Policy getAdministratorLoginIp() {
            return administratorLoginIp;
        }

        public void setAdministratorLoginIp(Policy administratorLoginIp) {
            this.administratorLoginIp = administratorLoginIp;
        }

        public Policy getAdministratorLoginIdentity() {
            return administratorLoginIdentity;
        }

        public void setAdministratorLoginIdentity(Policy administratorLoginIdentity) {
            this.administratorLoginIdentity = administratorLoginIdentity;
        }

        public Policy getAdministratorLoginFailure() {
            return administratorLoginFailure;
        }

        public void setAdministratorLoginFailure(Policy administratorLoginFailure) {
            this.administratorLoginFailure = administratorLoginFailure;
        }

        public Policy getUserRefresh() {
            return userRefresh;
        }

        public void setUserRefresh(Policy userRefresh) {
            this.userRefresh = userRefresh;
        }

        public Policy getAdministratorRefresh() {
            return administratorRefresh;
        }

        public void setAdministratorRefresh(Policy administratorRefresh) {
            this.administratorRefresh = administratorRefresh;
        }

        public Policy getAgentExchange() {
            return agentExchange;
        }

        public void setAgentExchange(Policy agentExchange) {
            this.agentExchange = agentExchange;
        }

        public Policy getUserOrderTransaction() {
            return userOrderTransaction;
        }

        public void setUserOrderTransaction(Policy userOrderTransaction) {
            this.userOrderTransaction = userOrderTransaction;
        }

        public Policy getUserReservationTransaction() {
            return userReservationTransaction;
        }

        public void setUserReservationTransaction(Policy userReservationTransaction) {
            this.userReservationTransaction = userReservationTransaction;
        }
    }

    public static class Policy {
        @Min(1)
        private int limit;
        @Min(1)
        private long windowSeconds;

        public Policy() {
        }

        public Policy(int limit, long windowSeconds) {
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
