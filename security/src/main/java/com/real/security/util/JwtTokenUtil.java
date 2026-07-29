package com.real.security.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.security.config.SecurityProperties;
import com.real.security.entity.CustomUserDetails;
import com.real.security.identity.IdentityType;
import com.real.security.identity.IssuedAccessToken;
import com.real.security.identity.ValidatedClientAssertion;
import com.real.security.identity.ValidatedToken;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JwtTokenUtil {
    private static final String ALGORITHM = "RS256";
    private static final int MAX_TOKEN_LENGTH = 16_384;
    private static final Set<String> ADMIN_AUTHORITIES = Set.of(
            "ROLE_ADMIN",
            "PERM_ADMIN_PRODUCT_READ",
            "PERM_ADMIN_PRODUCT_WRITE",
            "PERM_ADMIN_FLASH_SALE_LOAD",
            "PERM_ADMIN_ORDER_READ",
            "PERM_ADMIN_USER_READ"
    );

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, PrivateKey> privateKeys = new ConcurrentHashMap<>();
    private final Map<String, PublicKey> publicKeys = new ConcurrentHashMap<>();

    @Autowired
    public JwtTokenUtil(SecurityProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    JwtTokenUtil(SecurityProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IssuedAccessToken issueUserAccess(CustomUserDetails principal) {
        return issue(
                IdentityType.USER_ACCESS,
                principal.getUserId(),
                principal.getUsername(),
                Set.of("ROLE_USER"),
                null,
                Set.of()
        );
    }

    public IssuedAccessToken issueAdministratorAccess(CustomUserDetails principal) {
        return issue(
                IdentityType.ADMINISTRATOR_ACCESS,
                principal.getUserId(),
                principal.getUsername(),
                ADMIN_AUTHORITIES,
                null,
                Set.of()
        );
    }

    public IssuedAccessToken issueAgentDelegation(
            long delegatedUserId,
            String username,
            String authorizedParty,
            Set<String> scopes
    ) {
        if (!StringUtils.hasText(authorizedParty) || scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("Agent delegation requires an authorized party and scopes");
        }
        return issue(
                IdentityType.AGENT_DELEGATION,
                delegatedUserId,
                username,
                Set.of(),
                authorizedParty,
                scopes
        );
    }

    public ValidatedToken validate(String token, IdentityType expectedIdentity) {
        SecurityProperties.JwtDomain domain = properties.domain(expectedIdentity);
        ParsedJwt parsed = parseAndVerify(
                token,
                domain.getIssuer(),
                domain.getAudience(),
                domain.getType(),
                domain.getVerificationKeyPaths(),
                domain.getTtlSeconds()
        );
        Claims claims = parsed.claims();
        requireTextClaim(claims, "token_use", domain.getTokenUse());
        long userId = parsePositiveUserId(claims.getSubject());
        String username = requireTextClaim(claims, "preferred_username");
        String authorizedParty = null;
        Set<String> scopes = Set.of();

        if (expectedIdentity == IdentityType.AGENT_DELEGATION) {
            authorizedParty = requireTextClaim(claims, "azp");
            if (!properties.getClientAssertion().getClientId().equals(authorizedParty)) {
                throw new MalformedJwtException("Unexpected authorized party");
            }
            scopes = parseScopes(requireTextClaim(claims, "scope"));
            if (scopes.isEmpty() || claims.containsKey("authorities") || claims.containsKey("roles")) {
                throw new MalformedJwtException("Invalid Agent Delegation claims");
            }
        } else {
            Set<String> authorities = parseStringSet(claims.get("authorities"));
            Set<String> expected = expectedIdentity == IdentityType.USER_ACCESS
                    ? Set.of("ROLE_USER")
                    : ADMIN_AUTHORITIES;
            if (!authorities.equals(expected) || claims.containsKey("azp") || claims.containsKey("scope")) {
                throw new MalformedJwtException("Invalid access authorities");
            }
        }

        return new ValidatedToken(
                expectedIdentity,
                userId,
                username,
                claims.getId(),
                claims.getExpiration().toInstant(),
                authorizedParty,
                scopes
        );
    }

    public ValidatedClientAssertion validateClientAssertion(String token) {
        SecurityProperties.ClientAssertion assertion = properties.getClientAssertion();
        ParsedJwt parsed = parseAndVerify(
                token,
                assertion.getIssuer(),
                assertion.getAudience(),
                assertion.getType(),
                assertion.getVerificationKeyPaths(),
                assertion.getMaxTtlSeconds()
        );
        Claims claims = parsed.claims();
        if (!assertion.getClientId().equals(claims.getSubject())) {
            throw new MalformedJwtException("Unexpected client assertion subject");
        }
        if (claims.containsKey("authorities") || claims.containsKey("roles") || claims.containsKey("scope")) {
            throw new MalformedJwtException("Client assertion contains access claims");
        }
        return new ValidatedClientAssertion(
                claims.getSubject(),
                claims.getId(),
                claims.getExpiration().toInstant()
        );
    }

    public String extractToken(String bearerToken) {
        if (!StringUtils.hasText(bearerToken)) {
            return null;
        }
        return bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken;
    }

    public static Set<String> administratorAuthorities() {
        return ADMIN_AUTHORITIES;
    }

    private IssuedAccessToken issue(
            IdentityType identityType,
            long userId,
            String username,
            Set<String> authorities,
            String authorizedParty,
            Set<String> scopes
    ) {
        if (userId <= 0 || !StringUtils.hasText(username)) {
            throw new IllegalArgumentException("A stable User identity is required");
        }
        SecurityProperties.JwtDomain domain = properties.domain(identityType);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(domain.getTtlSeconds());
        String jti = UUID.randomUUID().toString();
        var builder = Jwts.builder()
                .setHeaderParam("typ", domain.getType())
                .setHeaderParam("kid", domain.getActiveKid())
                .setIssuer(domain.getIssuer())
                .setAudience(domain.getAudience())
                .setSubject(Long.toString(userId))
                .setIssuedAt(Date.from(issuedAt))
                .setNotBefore(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .setId(jti)
                .claim("token_use", domain.getTokenUse())
                .claim("preferred_username", username);

        if (identityType == IdentityType.AGENT_DELEGATION) {
            List<String> sortedScopes = scopes.stream().sorted().toList();
            builder.claim("azp", authorizedParty).claim("scope", String.join(" ", sortedScopes));
        } else {
            builder.claim("authorities", authorities.stream().sorted().toList());
        }

        String token = builder
                .signWith(loadPrivateKey(domain.getPrivateKeyPath()), SignatureAlgorithm.RS256)
                .compact();
        return new IssuedAccessToken(token, jti, expiresAt);
    }

    private ParsedJwt parseAndVerify(
            String token,
            String issuer,
            String audience,
            String expectedType,
            Map<String, String> verificationKeyPaths,
            long maxTtlSeconds
    ) {
        Header header = parseHeader(token);
        if (!ALGORITHM.equals(header.algorithm())) {
            throw new UnsupportedJwtException("JWT algorithm is not allowed");
        }
        if (!expectedType.equals(header.type())) {
            throw new UnsupportedJwtException("JWT type is not allowed");
        }
        String keyPath = verificationKeyPaths == null ? null : verificationKeyPaths.get(header.keyId());
        if (!StringUtils.hasText(keyPath)) {
            throw new UnsupportedJwtException("JWT key id is not recognized");
        }
        Claims claims = Jwts.parserBuilder()
                .setAllowedClockSkewSeconds(properties.getClockSkewSeconds())
                .requireIssuer(issuer)
                .requireAudience(audience)
                .setSigningKey(loadPublicKey(keyPath))
                .build()
                .parseClaimsJws(token)
                .getBody();
        validateRequiredTimesAndIdentifiers(claims, maxTtlSeconds);
        return new ParsedJwt(claims);
    }

    private Header parseHeader(String token) {
        if (!StringUtils.hasText(token) || token.length() > MAX_TOKEN_LENGTH) {
            throw new MalformedJwtException("JWT is missing or too large");
        }
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty() || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw new MalformedJwtException("JWT compact serialization is invalid");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(segments[0]);
            Map<String, Object> values = objectMapper.readValue(decoded, new TypeReference<>() {
            });
            Object algorithm = values.get("alg");
            Object type = values.get("typ");
            Object keyId = values.get("kid");
            if (!(algorithm instanceof String)
                    || !(type instanceof String)
                    || !(keyId instanceof String)
                    || !StringUtils.hasText((String) keyId)) {
                throw new MalformedJwtException("JWT protected header is incomplete");
            }
            if (values.containsKey("crit")) {
                throw new UnsupportedJwtException("Critical JWT headers are not supported");
            }
            return new Header((String) algorithm, (String) type, (String) keyId);
        } catch (IllegalArgumentException | IOException exception) {
            throw new MalformedJwtException("JWT protected header is invalid", exception);
        }
    }

    private void validateRequiredTimesAndIdentifiers(Claims claims, long maxTtlSeconds) {
        if (claims.getIssuedAt() == null
                || claims.getNotBefore() == null
                || claims.getExpiration() == null
                || !StringUtils.hasText(claims.getId())
                || !StringUtils.hasText(claims.getSubject())) {
            throw new MalformedJwtException("JWT required claims are missing");
        }
        Instant now = clock.instant();
        Instant issuedAt = claims.getIssuedAt().toInstant();
        Instant notBefore = claims.getNotBefore().toInstant();
        Instant expiresAt = claims.getExpiration().toInstant();
        Duration skew = Duration.ofSeconds(properties.getClockSkewSeconds());
        if (issuedAt.isAfter(now.plus(skew))
                || notBefore.isBefore(issuedAt.minus(skew))
                || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(
                        Duration.ofSeconds(maxTtlSeconds).plus(skew)
                ) > 0) {
            throw new MalformedJwtException("JWT time claims are invalid");
        }
    }

    private long parsePositiveUserId(String subject) {
        try {
            long userId = Long.parseLong(subject);
            if (userId <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw new MalformedJwtException("JWT subject is not a stable User ID", exception);
        }
    }

    private String requireTextClaim(Claims claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new MalformedJwtException("JWT claim is missing");
        }
        return text;
    }

    private void requireTextClaim(Claims claims, String name, String expected) {
        if (!expected.equals(requireTextClaim(claims, name))) {
            throw new MalformedJwtException("JWT claim has an unexpected value");
        }
    }

    private Set<String> parseScopes(String value) {
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : value.split(" ")) {
            if (StringUtils.hasText(scope)) {
                scopes.add(scope);
            }
        }
        return Set.copyOf(scopes);
    }

    private Set<String> parseStringSet(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return Set.of();
        }
        List<String> values = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof String text) || !StringUtils.hasText(text)) {
                throw new MalformedJwtException("JWT authority claim is invalid");
            }
            values.add(text);
        }
        return Set.copyOf(values);
    }

    private PrivateKey loadPrivateKey(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalStateException("JWT signing private key path is not configured");
        }
        return privateKeys.computeIfAbsent(path, this::readPrivateKey);
    }

    private PublicKey loadPublicKey(String path) {
        return publicKeys.computeIfAbsent(path, this::readPublicKey);
    }

    private PrivateKey readPrivateKey(String path) {
        try {
            String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
            byte[] bytes = decodePem(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load configured JWT signing key", exception);
        }
    }

    private PublicKey readPublicKey(String path) {
        try {
            String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
            byte[] bytes = decodePem(pem, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new JwtException("Could not load configured JWT verification key", exception);
        }
    }

    private byte[] decodePem(String pem, String label) {
        String normalized = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        if (normalized.isEmpty() || normalized.toUpperCase(Locale.ROOT).contains("PRIVATE")) {
            throw new IllegalArgumentException("PEM content is invalid");
        }
        return Base64.getDecoder().decode(normalized);
    }

    private record Header(String algorithm, String type, String keyId) {
    }

    private record ParsedJwt(Claims claims) {
    }
}
